"""
單元測試 — SignalRouter 回覆訊息處理（第一層防護）

測試覆蓋：
1. 回覆訊息跳過 embeds（避免引用的舊訊號混入）
2. 回覆訊息的 content 正常傳給 AI parser
3. 非回覆訊息仍串接 embeds
4. 舊格式訊息（無 has_reference）向下相容
5. 真實場景：回覆止盈出局 + 引用舊開單訊號
6. 回覆訊息 content 為空時不送 API
7. 回覆訊息 content 含完整開單訊號（非引用）正常處理
8. 多個 embeds 全部被跳過
"""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest

from src.config import DiscordConfig
from src.signal_router import SignalRouter


def _make_msg(
    content: str = "",
    embeds: list[dict] | None = None,
    channel_id: str = "ch123",
    message_id: str = "msg001",
    has_reference: bool = False,
    referenced_content: str = "",
) -> dict:
    """建構一條 Discord 訊息 dict（含回覆欄位）。"""
    return {
        "id": message_id,
        "channel_id": channel_id,
        "guild_id": "guild1",
        "author_id": "author1",
        "author_name": "TestUser",
        "content": content,
        "embeds": embeds or [],
        "timestamp": "2025-01-01T00:00:00",
        "has_reference": has_reference,
        "referenced_content": referenced_content,
    }


class TestReplyMessageHandling:
    """回覆訊息處理測試。"""

    def setup_method(self):
        self.api_client = MagicMock()
        self.api_client.send_signal = AsyncMock()
        self.api_client.send_trade = AsyncMock()
        self.ai_parser = AsyncMock()

    def _make_router(self) -> SignalRouter:
        config = DiscordConfig(channel_ids=["ch123"])
        return SignalRouter(
            discord_config=config,
            api_client=self.api_client,
            dry_run=False,
            ai_parser=self.ai_parser,
        )

    @pytest.mark.asyncio
    async def test_reply_message_ignores_embeds(self):
        """回覆訊息應只處理 content，跳過 embeds（引用的舊訊號不應混入）。"""
        router = self._make_router()
        self.ai_parser.parse.return_value = {"action": "CLOSE", "symbol": "BTCUSDT"}
        self.api_client.send_trade.return_value = MagicMock(success=True, summary="OK")

        msg = _make_msg(
            content="全部止盈出局\nBTC实时价格: 63500",
            embeds=[{
                "title": "陈哥合约交易策略",
                "description": "BTC，63200-62900附近，做多\n止损预计: 61500\n止盈预计: 66900",
            }],
            has_reference=True,
            referenced_content="BTC，63200-62900附近，做多\n止损预计: 61500",
        )

        await router.handle_message(msg)

        # AI parser 應該只收到新訊息 content，不含 embeds 的舊訊號
        call_args = self.ai_parser.parse.call_args[0][0]
        assert "63200" not in call_args
        assert "做多" not in call_args
        assert "止盈出局" in call_args

    @pytest.mark.asyncio
    async def test_reply_message_processes_content(self):
        """回覆訊息的 content 應正常傳給 AI parser。"""
        router = self._make_router()
        self.ai_parser.parse.return_value = {"action": "CLOSE", "symbol": "BTCUSDT"}
        self.api_client.send_trade.return_value = MagicMock(success=True, summary="OK")

        msg = _make_msg(
            content="✅止盈出局✅\nBTC实时价格: 63500",
            has_reference=True,
            referenced_content="BTC，63200-62900附近，做多",
        )

        await router.handle_message(msg)

        self.ai_parser.parse.assert_called_once()
        call_args = self.ai_parser.parse.call_args[0][0]
        assert "止盈出局" in call_args

    @pytest.mark.asyncio
    async def test_non_reply_includes_embeds(self):
        """非回覆訊息應串接 embeds（保持原有行為）。"""
        router = self._make_router()
        self.ai_parser.parse.return_value = {
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "LONG",
            "entry_price": 63200,
        }
        self.api_client.send_trade.return_value = MagicMock(success=True, summary="OK")

        msg = _make_msg(
            content="",
            embeds=[{
                "title": "陈哥合约交易策略",
                "description": "BTC，63200-62900附近，做多\n止损预计: 61500",
            }],
            has_reference=False,
        )

        await router.handle_message(msg)

        self.ai_parser.parse.assert_called_once()
        call_args = self.ai_parser.parse.call_args[0][0]
        assert "63200" in call_args
        assert "做多" in call_args

    @pytest.mark.asyncio
    async def test_reply_flag_not_present_defaults_false(self):
        """舊格式訊息（無 has_reference 欄位）應向下相容，串接 embeds。"""
        router = self._make_router()
        self.ai_parser.parse.return_value = {
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "LONG",
            "entry_price": 63200,
        }
        self.api_client.send_trade.return_value = MagicMock(success=True, summary="OK")

        # 模擬舊格式：沒有 has_reference 欄位
        msg = {
            "id": "msg001",
            "channel_id": "ch123",
            "guild_id": "guild1",
            "author_id": "author1",
            "author_name": "TestUser",
            "content": "",
            "embeds": [{"title": "", "description": "BTC，63200附近，做多"}],
            "timestamp": "2025-01-01T00:00:00",
        }

        await router.handle_message(msg)

        self.ai_parser.parse.assert_called_once()
        call_args = self.ai_parser.parse.call_args[0][0]
        assert "做多" in call_args


class TestReplyRealWorldScenarios:
    """真實場景回覆訊息測試。"""

    def setup_method(self):
        self.api_client = MagicMock()
        self.api_client.send_signal = AsyncMock()
        self.api_client.send_trade = AsyncMock()
        self.ai_parser = AsyncMock()

    def _make_router(self) -> SignalRouter:
        config = DiscordConfig(channel_ids=["ch123"])
        return SignalRouter(
            discord_config=config,
            api_client=self.api_client,
            dry_run=False,
            ai_parser=self.ai_parser,
        )

    @pytest.mark.asyncio
    async def test_reply_close_with_quoted_entry_signal(self):
        """真實場景：回覆止盈出局，引用的是原始開單訊號。

        這是觸發 bug 的核心場景：
        - 新訊息：「全部止盈出局✅ BTC实时价格: 63500」
        - 引用訊息（embed）：「BTC，63200-62900附近，做多 止损 61500 止盈 66900」
        AI parser 應只看到止盈出局，不應看到做多。
        """
        router = self._make_router()
        self.ai_parser.parse.return_value = {"action": "CLOSE", "symbol": "BTCUSDT"}
        self.api_client.send_trade.return_value = MagicMock(success=True, summary="OK")

        msg = _make_msg(
            content="全部止盈出局\n不过夜持仓，止盈休息了。\n✅ 止盈出局 ✅\nBTC实时价格: 63500",
            embeds=[{
                "title": "⚠️⚠️⚠️ 陈哥合约交易策略 ⚠️⚠️⚠️",
                "description": "BTC，63200-62900附近，做多\n止损预计: 61500\n止盈预计: 66900",
            }],
            has_reference=True,
            referenced_content="⚠️⚠️⚠️\n陈哥合约交易策略\nBTC，63200-62900附近，做多\n止损预计: 61500\n止盈预计: 66900\n⚠️⚠️⚠️",
            message_id="msg_close_reply",
        )

        await router.handle_message(msg)

        self.ai_parser.parse.assert_called_once()
        call_args = self.ai_parser.parse.call_args[0][0]
        # 不應包含舊訊號的任何內容
        assert "63200" not in call_args
        assert "62900" not in call_args
        assert "做多" not in call_args
        assert "61500" not in call_args
        assert "66900" not in call_args
        # 應包含新訊息的止盈出局
        assert "止盈出局" in call_args
        assert "63500" in call_args

    @pytest.mark.asyncio
    async def test_reply_with_empty_content_skips(self):
        """回覆訊息如果 content 為空（只有引用、沒有新文字），不送 API。"""
        router = self._make_router()

        msg = _make_msg(
            content="",
            embeds=[{
                "title": "陈哥合约交易策略",
                "description": "BTC，63200附近，做多",
            }],
            has_reference=True,
            referenced_content="BTC，63200附近，做多",
            message_id="msg_empty_reply",
        )

        await router.handle_message(msg)

        # content 為空 → 不該呼叫 AI parser 或 API
        self.ai_parser.parse.assert_not_called()
        self.api_client.send_trade.assert_not_called()

    @pytest.mark.asyncio
    async def test_reply_with_new_entry_in_content(self):
        """回覆訊息 content 本身含完整開單訊號時應正常處理。

        例如：訊號源回覆舊訊息但 content 是新的開單指令。
        """
        router = self._make_router()
        self.ai_parser.parse.return_value = {
            "action": "ENTRY",
            "symbol": "ETHUSDT",
            "side": "SHORT",
            "entry_price": 2560,
            "stop_loss": 2610,
        }
        self.api_client.send_trade.return_value = MagicMock(success=True, summary="OK")

        msg = _make_msg(
            content="ETH，2560附近，做空\n止损预计：2610\n止盈预计：2456",
            embeds=[],
            has_reference=True,
            referenced_content="上一單 BTC 做多已止盈",
            message_id="msg_new_entry_reply",
        )

        await router.handle_message(msg)

        self.ai_parser.parse.assert_called_once()
        call_args = self.ai_parser.parse.call_args[0][0]
        assert "2560" in call_args
        assert "做空" in call_args

    @pytest.mark.asyncio
    async def test_reply_multiple_embeds_all_ignored(self):
        """回覆訊息有多個 embeds 時，全部跳過。"""
        router = self._make_router()
        self.ai_parser.parse.return_value = {"action": "CLOSE", "symbol": "BTCUSDT"}
        self.api_client.send_trade.return_value = MagicMock(success=True, summary="OK")

        msg = _make_msg(
            content="止盈出局",
            embeds=[
                {"title": "開單訊號", "description": "BTC 做多 63200"},
                {"title": "止損提醒", "description": "止损设置 61500"},
                {"title": "盈虧報告", "description": "本周盈利 3R"},
            ],
            has_reference=True,
            message_id="msg_multi_embed",
        )

        await router.handle_message(msg)

        call_args = self.ai_parser.parse.call_args[0][0]
        # 所有 embed 內容都不應出現
        assert "63200" not in call_args
        assert "61500" not in call_args
        assert "3R" not in call_args
        # 只有 content
        assert call_args == "止盈出局"
