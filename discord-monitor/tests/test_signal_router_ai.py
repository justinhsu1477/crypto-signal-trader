"""
單元測試 — SignalRouter AI 流程 + Queue 整合 + _identify_type

測試覆蓋：
1. AI parser 成功 → send_trade
2. AI parser 失敗 → regex fallback → send_signal
3. AI parser 返回 INFO → TradeActionDetector 補救 → CLOSE
4. AI parser 返回 None → TradeActionDetector 補救 → CLOSE
5. AI parser INFO + detector 無補救 → skip
6. _identify_type emoji/keyword 路由
7. AI trade 失敗 → enqueue（status_code=0）
8. AI trade 成功 → 不 enqueue
9. Regex fallback 失敗 → enqueue
10. Guild/Author filter
"""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from src.api_client import ExecutionResult
from src.config import DiscordConfig
from src.signal_router import SignalRouter, SIGNAL_TYPES, KEYWORD_SIGNALS


def _make_msg(
    content: str = "",
    embeds: list[dict] | None = None,
    channel_id: str = "ch123",
    message_id: str = "msg001",
    guild_id: str = "guild1",
    author_id: str = "author1",
    has_reference: bool = False,
) -> dict:
    return {
        "id": message_id,
        "channel_id": channel_id,
        "guild_id": guild_id,
        "author_id": author_id,
        "author_name": "TestUser",
        "content": content,
        "embeds": embeds or [],
        "timestamp": "2025-01-01T00:00:00",
        "has_reference": has_reference,
    }


class TestAiParserFlow:
    """AI parser 流程測試（_forward_signal 核心路徑）。"""

    def setup_method(self):
        self.api_client = AsyncMock()
        self.ai_parser = AsyncMock()
        self.mock_queue = MagicMock()
        self.mock_queue.enqueue = MagicMock(return_value=True)

    def _make_router(self, ai_parser=None, signal_queue=None) -> SignalRouter:
        config = DiscordConfig(channel_ids=["ch123"])
        return SignalRouter(
            discord_config=config,
            api_client=self.api_client,
            dry_run=False,
            ai_parser=ai_parser,
            signal_queue=signal_queue,
        )

    @pytest.mark.asyncio
    async def test_ai_parse_entry_sends_trade(self):
        """AI 成功解析 ENTRY → send_trade。"""
        self.ai_parser.parse.return_value = {
            "action": "ENTRY", "symbol": "BTCUSDT",
            "side": "SHORT", "entry_price": 68500, "stop_loss": 70000,
        }
        self.api_client.send_trade.return_value = ExecutionResult(
            success=True, status_code=200, summary="OK", error="",
        )

        router = self._make_router(ai_parser=self.ai_parser)
        await router._forward_signal("BTC 68500 做空", source={"message_id": "m1"})

        self.api_client.send_trade.assert_called_once()
        self.api_client.send_signal.assert_not_called()

    @pytest.mark.asyncio
    async def test_ai_parse_close_sends_trade(self):
        """AI 成功解析 CLOSE → send_trade。"""
        self.ai_parser.parse.return_value = {
            "action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 1.0,
        }
        self.api_client.send_trade.return_value = ExecutionResult(
            success=True, status_code=200, summary="Closed", error="",
        )

        router = self._make_router(ai_parser=self.ai_parser)
        await router._forward_signal("平倉 BTC", source={"message_id": "m2"})

        self.api_client.send_trade.assert_called_once()

    @pytest.mark.asyncio
    async def test_ai_parse_none_falls_to_regex(self):
        """AI 返回 None + TradeActionDetector 無補救 → regex fallback → send_signal。"""
        self.ai_parser.parse.return_value = None
        self.api_client.send_signal.return_value = ExecutionResult(
            success=True, status_code=200, summary="Regex OK", error="",
        )

        router = self._make_router(ai_parser=self.ai_parser)
        # 用不包含「出局」「平倉」關鍵字的內容，讓 detector 也無法補救
        await router._forward_signal("some random text", source={"message_id": "m3"})

        # AI 失敗 + detector 無補救 → 走 regex fallback
        self.api_client.send_signal.assert_called_once()
        self.api_client.send_trade.assert_not_called()

    @pytest.mark.asyncio
    async def test_ai_parse_info_skipped(self):
        """AI 判定 INFO + TradeActionDetector 無補救 → skip（不打 API）。"""
        self.ai_parser.parse.return_value = {
            "action": "INFO", "symbol": "BTCUSDT",
        }

        router = self._make_router(ai_parser=self.ai_parser)
        await router._forward_signal("止損成交通知", source={"message_id": "m4"})

        self.api_client.send_trade.assert_not_called()
        self.api_client.send_signal.assert_not_called()

    @pytest.mark.asyncio
    async def test_ai_info_detector_refines_to_close(self):
        """AI 判定 INFO → TradeActionDetector 偵測到「出局」→ CLOSE → send_trade。"""
        self.ai_parser.parse.return_value = {
            "action": "INFO", "symbol": "BTCUSDT",
        }
        self.api_client.send_trade.return_value = ExecutionResult(
            success=True, status_code=200, summary="Closed", error="",
        )

        router = self._make_router(ai_parser=self.ai_parser)
        # 「出局」會被 TradeActionDetector 偵測為 CLOSE
        await router._forward_signal("短線止盈出局", source={"message_id": "m5"})

        # detector 補救 → send_trade 被呼叫
        self.api_client.send_trade.assert_called_once()
        call_payload = self.api_client.send_trade.call_args[0][0]
        assert call_payload["action"] == "CLOSE"

    @pytest.mark.asyncio
    async def test_ai_none_detector_refines_to_close(self):
        """AI 返回 None → TradeActionDetector 偵測到「平倉」→ CLOSE → send_trade。"""
        self.ai_parser.parse.return_value = None
        self.api_client.send_trade.return_value = ExecutionResult(
            success=True, status_code=200, summary="Closed", error="",
        )

        router = self._make_router(ai_parser=self.ai_parser)
        await router._forward_signal("全部平倉離場", source={"message_id": "m6"})

        self.api_client.send_trade.assert_called_once()
        call_payload = self.api_client.send_trade.call_args[0][0]
        assert call_payload["action"] == "CLOSE"
        assert call_payload["symbol"] == "BTCUSDT"

    @pytest.mark.asyncio
    async def test_no_ai_parser_uses_regex_only(self):
        """無 AI parser → regex mode → 走 send_signal。"""
        self.api_client.send_signal.return_value = ExecutionResult(
            success=True, status_code=200, summary="Regex OK", error="",
        )

        router = self._make_router(ai_parser=None)
        # 📢 is ENTRY emoji prefix in SIGNAL_TYPES
        await router._forward_signal("📢 BTC SHORT 68500", source={"message_id": "m7"})

        self.api_client.send_signal.assert_called_once()
        self.api_client.send_trade.assert_not_called()


class TestAiTradeQueueIntegration:
    """AI trade 失敗時 enqueue 測試。"""

    def setup_method(self):
        self.api_client = AsyncMock()
        self.ai_parser = AsyncMock()
        self.mock_queue = MagicMock()
        self.mock_queue.enqueue = MagicMock(return_value=True)

    def _make_router(self, ai_parser=None, signal_queue=None) -> SignalRouter:
        config = DiscordConfig(channel_ids=["ch123"])
        return SignalRouter(
            discord_config=config,
            api_client=self.api_client,
            dry_run=False,
            ai_parser=ai_parser,
            signal_queue=signal_queue,
        )

    @pytest.mark.asyncio
    async def test_ai_trade_fail_enqueues(self):
        """AI send_trade 失敗（status_code=0）→ enqueue。"""
        self.ai_parser.parse.return_value = {
            "action": "ENTRY", "symbol": "BTCUSDT",
            "side": "SHORT", "entry_price": 68500,
        }
        self.api_client.send_trade.return_value = ExecutionResult(
            success=False, status_code=0, summary="", error="All retries failed",
        )

        router = self._make_router(ai_parser=self.ai_parser, signal_queue=self.mock_queue)
        await router._forward_signal("BTC SHORT 68500", source={"message_id": "m1"})

        self.mock_queue.enqueue.assert_called_once()
        call_kwargs = self.mock_queue.enqueue.call_args.kwargs
        assert call_kwargs["call_type"] == "send_trade"
        assert call_kwargs["payload"]["action"] == "ENTRY"

    @pytest.mark.asyncio
    async def test_ai_trade_4xx_no_enqueue(self):
        """AI send_trade 返回 4xx → 不 enqueue。"""
        self.ai_parser.parse.return_value = {
            "action": "ENTRY", "symbol": "BTCUSDT",
            "side": "LONG", "entry_price": 95000,
        }
        self.api_client.send_trade.return_value = ExecutionResult(
            success=False, status_code=400, summary="", error="bad request",
        )

        router = self._make_router(ai_parser=self.ai_parser, signal_queue=self.mock_queue)
        await router._forward_signal("BTC LONG 95000", source={"message_id": "m2"})

        self.mock_queue.enqueue.assert_not_called()

    @pytest.mark.asyncio
    async def test_ai_trade_success_no_enqueue(self):
        """AI send_trade 成功 → 不 enqueue。"""
        self.ai_parser.parse.return_value = {
            "action": "ENTRY", "symbol": "BTCUSDT",
            "side": "SHORT", "entry_price": 68500,
        }
        self.api_client.send_trade.return_value = ExecutionResult(
            success=True, status_code=200, summary="OK", error="",
        )

        router = self._make_router(ai_parser=self.ai_parser, signal_queue=self.mock_queue)
        await router._forward_signal("BTC SHORT 68500", source={"message_id": "m3"})

        self.mock_queue.enqueue.assert_not_called()

    @pytest.mark.asyncio
    async def test_regex_fallback_fail_enqueues(self):
        """Regex fallback send_signal 失敗（status_code=0）→ enqueue。"""
        self.api_client.send_signal.return_value = ExecutionResult(
            success=False, status_code=0, summary="", error="All retries failed",
        )

        # 無 AI parser → regex mode
        router = self._make_router(ai_parser=None, signal_queue=self.mock_queue)
        await router._forward_signal("📢 BTC SHORT", source={"message_id": "m4"})

        self.mock_queue.enqueue.assert_called_once()
        call_kwargs = self.mock_queue.enqueue.call_args.kwargs
        assert call_kwargs["call_type"] == "send_signal"

    @pytest.mark.asyncio
    async def test_no_queue_configured_no_error(self):
        """signal_queue=None 時 API 失敗不應拋出例外。"""
        self.ai_parser.parse.return_value = {
            "action": "ENTRY", "symbol": "BTCUSDT",
            "side": "SHORT", "entry_price": 68500,
        }
        self.api_client.send_trade.return_value = ExecutionResult(
            success=False, status_code=0, summary="", error="All retries failed",
        )

        router = self._make_router(ai_parser=self.ai_parser, signal_queue=None)
        # 不應拋出例外
        await router._forward_signal("BTC SHORT 68500", source={"message_id": "m5"})


class TestIdentifyType:
    """_identify_type emoji/keyword 路由測試（regex-only 模式下使用）。"""

    def setup_method(self):
        config = DiscordConfig(channel_ids=["ch123"])
        self.router = SignalRouter(
            discord_config=config,
            api_client=MagicMock(),
            dry_run=False,
        )

    def test_entry_emoji(self):
        """📢 → ENTRY。"""
        assert self.router._identify_type("📢 交易訊號發布 BTC SHORT") == "ENTRY"

    def test_cancel_emoji(self):
        """⚠️ → CANCEL。"""
        assert self.router._identify_type("⚠️ 掛單取消 BTCUSDT") == "CANCEL"

    def test_rocket_emoji_info(self):
        """🚀 → INFO。"""
        assert self.router._identify_type("🚀 訊號成交通知") == "INFO"

    def test_stop_emoji_info(self):
        """🛑 → INFO。"""
        assert self.router._identify_type("🛑 止損出場 BTCUSDT") == "INFO"

    def test_money_emoji_info(self):
        """💰 → INFO。"""
        assert self.router._identify_type("💰 盈虧更新 +500 USDT") == "INFO"

    def test_keyword_modify(self):
        """TP-SL 修改 → MODIFY。"""
        assert self.router._identify_type("TP-SL 修改 BTCUSDT 止損 68000") == "MODIFY"

    def test_keyword_modify_no_space(self):
        """TP-SL修改（無空格）→ MODIFY。"""
        assert self.router._identify_type("TP-SL修改 BTCUSDT") == "MODIFY"

    def test_unknown_content(self):
        """不匹配任何 emoji/keyword → UNKNOWN。"""
        assert self.router._identify_type("hello world") == "UNKNOWN"

    def test_empty_content(self):
        assert self.router._identify_type("") == "UNKNOWN"

    def test_whitespace_content(self):
        assert self.router._identify_type("   ") == "UNKNOWN"


class TestGuildAuthorFilter:
    """guild_ids / author_ids 過濾測試。"""

    def setup_method(self):
        self.api_client = AsyncMock()
        self.ai_parser = AsyncMock()

    @pytest.mark.asyncio
    async def test_guild_filter_blocks(self):
        """guild_ids 設定時，不在白名單的 guild 被攔截。"""
        config = DiscordConfig(channel_ids=["ch123"], guild_ids=["guild-allowed"])
        router = SignalRouter(config, self.api_client, ai_parser=self.ai_parser)

        msg = _make_msg(guild_id="guild-other")
        await router.handle_message(msg)

        self.ai_parser.parse.assert_not_called()

    @pytest.mark.asyncio
    async def test_guild_filter_passes(self):
        """guild_ids 設定時，白名單內的 guild 放行。"""
        config = DiscordConfig(channel_ids=["ch123"], guild_ids=["guild1"])
        self.ai_parser.parse.return_value = {"action": "INFO"}
        router = SignalRouter(config, self.api_client, ai_parser=self.ai_parser)

        msg = _make_msg(guild_id="guild1", content="some signal")
        await router.handle_message(msg)

        self.ai_parser.parse.assert_called_once()

    @pytest.mark.asyncio
    async def test_author_filter_blocks(self):
        """author_ids 設定時，不在白名單的 author 被攔截。"""
        config = DiscordConfig(channel_ids=["ch123"], author_ids=["author-allowed"])
        router = SignalRouter(config, self.api_client, ai_parser=self.ai_parser)

        msg = _make_msg(author_id="author-other", content="BTC SHORT")
        await router.handle_message(msg)

        self.ai_parser.parse.assert_not_called()

    @pytest.mark.asyncio
    async def test_author_filter_passes(self):
        """author_ids 設定時，白名單內的 author 放行。"""
        config = DiscordConfig(channel_ids=["ch123"], author_ids=["author1"])
        self.ai_parser.parse.return_value = {"action": "INFO"}
        router = SignalRouter(config, self.api_client, ai_parser=self.ai_parser)

        msg = _make_msg(author_id="author1", content="BTC signal")
        await router.handle_message(msg)

        self.ai_parser.parse.assert_called_once()

    @pytest.mark.asyncio
    async def test_no_guild_filter_allows_all(self):
        """guild_ids 為空 → 所有 guild 放行。"""
        config = DiscordConfig(channel_ids=["ch123"], guild_ids=[])
        self.ai_parser.parse.return_value = {"action": "INFO"}
        router = SignalRouter(config, self.api_client, ai_parser=self.ai_parser)

        msg = _make_msg(guild_id="any-guild", content="BTC signal")
        await router.handle_message(msg)

        self.ai_parser.parse.assert_called_once()

    @pytest.mark.asyncio
    async def test_dedup_message_id(self):
        """相同 message_id 第二次呼叫 → 不處理（dedup）。"""
        config = DiscordConfig(channel_ids=["ch123"])
        self.ai_parser.parse.return_value = {"action": "INFO"}
        router = SignalRouter(config, self.api_client, ai_parser=self.ai_parser)

        msg = _make_msg(content="BTC signal", message_id="dup-001")
        await router.handle_message(msg)
        await router.handle_message(msg)

        # 只呼叫一次
        assert self.ai_parser.parse.call_count == 1

    @pytest.mark.asyncio
    async def test_empty_content_skipped(self):
        """空內容 + 空 embeds → 不處理。"""
        config = DiscordConfig(channel_ids=["ch123"])
        router = SignalRouter(config, self.api_client, ai_parser=self.ai_parser)

        msg = _make_msg(content="", embeds=[])
        await router.handle_message(msg)

        self.ai_parser.parse.assert_not_called()
