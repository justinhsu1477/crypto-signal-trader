"""
單元測試 — SignalRouter 回覆訊息處理（多層防護）

測試覆蓋：
Layer 1: 回覆訊息（has_reference）跳過 embeds
Layer 2: 轉發訊息（has_snapshots）跳過 embeds
Layer 3: embed 含歷史時間戳 → 跳過該 embed
Layer 4: embed content-hash 去重 → 跳過已處理過的訊號內容
真實場景：Bot/APP 引用舊訊號的 embed 不觸發重新下單
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
    has_snapshots: bool = False,
    referenced_content: str = "",
) -> dict:
    """建構一條 Discord 訊息 dict（含回覆/轉發欄位）。"""
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
        "has_snapshots": has_snapshots,
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


class TestBotAppEmbedQuoteBug:
    """Bug 復現：Bot/APP 用 embed 引用舊訊號，非標準 reply。

    真實案例 2026-03-03：
    - 00:48 陳哥發 ENTRY 訊號（BTC 70000 做空）→ 系統正確執行
    - 09:54 陳哥 APP 發閒聊訊息，embed 引用了 00:48 的舊訊號
    - has_reference=False（非標準回覆），embeds 含舊訊號
    - 系統錯誤地把 embed 裡的舊訊號當新訊號重新執行
    """

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
    async def test_exact_bug_scenario_20260303(self):
        """完整復現 2026-03-03 bug：APP embed 引用舊訊號帶時間戳。

        9:54 AM 訊息結構：
        - content: 閒聊（翻仓班名額滿了）
        - embeds[0]: 引用 01:15 訊息（還有最後三個名額）
        - embeds[1]: 引用 00:48 訊號（BTC 做空 70000）← 這個不該被執行
        """
        router = self._make_router()
        self.ai_parser.parse.return_value = {"action": "INFO"}

        msg = _make_msg(
            content="翻仓班20名额满了，已经不收了，大家不用私信了🤝\n剩余仓位可以继续持有，有变动我会在会员群通知。",
            embeds=[
                {
                    "title": "陈哥合约频道 2026-03-03 01:15",
                    "description": "还有最后三个名额，明天报名完了就关闭该通道，仅限会员，抄袭我的不是陈哥本人会员就不要来问了。",
                },
                {
                    "title": "陈哥合约频道 2026-03-03 00:48",
                    "description": "⚠️⚠️⚠️⚠️⚠️⚠️\n陈哥合约交易策略\nBTC，70000附近，做空\n止損預計: 71700\n止盈預計: 65000/63400\n⚠️⚠️⚠️⚠️⚠️⚠️",
                },
            ],
            has_reference=False,  # 非標準回覆！這是 bug 的核心
            has_snapshots=False,
            message_id="msg_bug_20260303",
        )

        await router.handle_message(msg)

        # AI parser 應該只收到閒聊內容，不含舊訊號
        call_args = self.ai_parser.parse.call_args[0][0]
        assert "70000" not in call_args, "舊訊號的價格不應出現"
        assert "做空" not in call_args, "舊訊號的方向不應出現"
        assert "71700" not in call_args, "舊訊號的止損不應出現"
        assert "65000" not in call_args, "舊訊號的止盈不應出現"
        assert "翻仓班" in call_args, "新訊息的 content 應被保留"

    @pytest.mark.asyncio
    async def test_embed_with_timestamp_in_description(self):
        """embed description 含時間戳也應被跳過（時間戳可能在 description 而非 title）。"""
        router = self._make_router()
        self.ai_parser.parse.return_value = {"action": "INFO"}

        msg = _make_msg(
            content="看看之前的單子",
            embeds=[{
                "title": "歷史訊號",
                "description": "2026-03-02 22:30\nBTC，68000附近，做多\n止損: 67000",
            }],
            has_reference=False,
            message_id="msg_ts_in_desc",
        )

        await router.handle_message(msg)

        call_args = self.ai_parser.parse.call_args[0][0]
        assert "68000" not in call_args
        assert "做多" not in call_args

    @pytest.mark.asyncio
    async def test_embed_without_timestamp_still_included(self):
        """正常 embed（無時間戳、非回覆）應照常包含。"""
        router = self._make_router()
        self.ai_parser.parse.return_value = {
            "action": "ENTRY", "symbol": "BTCUSDT", "side": "SHORT",
            "entry_price": 70000, "stop_loss": 71700,
        }
        self.api_client.send_trade.return_value = MagicMock(success=True, summary="OK")

        msg = _make_msg(
            content="",
            embeds=[{
                "title": "⚠️ 陈哥合约交易策略",
                "description": "BTC，70000附近，做空\n止損預計: 71700",
            }],
            has_reference=False,
            message_id="msg_normal_embed",
        )

        await router.handle_message(msg)

        self.ai_parser.parse.assert_called_once()
        call_args = self.ai_parser.parse.call_args[0][0]
        assert "70000" in call_args
        assert "做空" in call_args


class TestSnapshotForwardedMessages:
    """Layer 2: Discord 轉發訊息（message_snapshots）防護。"""

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
    async def test_forwarded_message_skips_embeds(self):
        """has_snapshots=True 時跳過所有 embeds。"""
        router = self._make_router()
        self.ai_parser.parse.return_value = {"action": "INFO"}

        msg = _make_msg(
            content="看看這個訊號",
            embeds=[{
                "title": "陈哥合约交易策略",
                "description": "BTC，70000附近，做空\n止損: 71700",
            }],
            has_reference=False,
            has_snapshots=True,
            message_id="msg_snapshot",
        )

        await router.handle_message(msg)

        call_args = self.ai_parser.parse.call_args[0][0]
        assert "70000" not in call_args
        assert "做空" not in call_args
        assert "看看這個訊號" in call_args

    @pytest.mark.asyncio
    async def test_forwarded_empty_content_skips(self):
        """轉發訊息 content 為空時直接跳過。"""
        router = self._make_router()

        msg = _make_msg(
            content="",
            embeds=[{
                "title": "舊訊號",
                "description": "BTC 做空 70000",
            }],
            has_snapshots=True,
            message_id="msg_snapshot_empty",
        )

        await router.handle_message(msg)

        self.ai_parser.parse.assert_not_called()
        self.api_client.send_trade.assert_not_called()


class TestContentHashDedup:
    """Layer 4: embed content-hash 去重。"""

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
    async def test_embed_content_hash_dedup(self):
        """先處理原始訊號，之後 embed 引用相同內容應被去重。"""
        router = self._make_router()

        signal_content = "BTC，70000附近，做空\n止損預計: 71700\n止盈預計: 65000/63400"

        # Step 1: 原始訊號（embed 方式，無時間戳）正常處理
        self.ai_parser.parse.return_value = {
            "action": "ENTRY", "symbol": "BTCUSDT", "side": "SHORT",
            "entry_price": 70000, "stop_loss": 71700,
        }
        self.api_client.send_trade.return_value = MagicMock(success=True, summary="OK")

        msg1 = _make_msg(
            content="",
            embeds=[{"title": "陈哥合约交易策略", "description": signal_content}],
            has_reference=False,
            message_id="msg_original_signal",
        )
        await router.handle_message(msg1)
        self.ai_parser.parse.assert_called_once()
        assert "70000" in self.ai_parser.parse.call_args[0][0]

        # Step 2: 後來的訊息 embed 引用相同內容（無時間戳版本）
        self.ai_parser.parse.reset_mock()
        self.ai_parser.parse.return_value = {"action": "INFO"}

        msg2 = _make_msg(
            content="翻仓班名额满了",
            embeds=[{"title": "陈哥合约交易策略", "description": signal_content}],
            has_reference=False,
            message_id="msg_later_quote",
        )
        await router.handle_message(msg2)

        # embed 應被 content-hash 去重，AI 只看到閒聊
        call_args = self.ai_parser.parse.call_args[0][0]
        assert "70000" not in call_args, "已處理過的訊號不應再出現在 content 中"
        assert "翻仓班" in call_args

    @pytest.mark.asyncio
    async def test_content_hash_whitespace_tolerance(self):
        """content-hash 去重應容忍空白差異。"""
        router = self._make_router()

        # 先記錄一個 hash
        router._record_content_hash("BTC，70000附近，做空\n止損: 71700")

        # 相同內容但空白不同
        h1 = router._content_hash("BTC，70000附近，做空\n止損: 71700")
        h2 = router._content_hash("BTC，70000附近，做空\n止損:  71700")
        h3 = router._content_hash("BTC，70000附近，做空 \n 止損: 71700")

        # 去除空白後 hash 應相同
        assert h1 == h2 == h3

    @pytest.mark.asyncio
    async def test_different_signal_not_deduped(self):
        """不同的訊號內容不應被去重。"""
        router = self._make_router()

        # 記錄 BTC 訊號
        router._record_content_hash("BTC，70000附近，做空")

        # ETH 訊號的 hash 應不同
        eth_hash = router._content_hash("ETH，2500附近，做多")
        assert eth_hash not in router._content_hashes


class TestMixedLayerProtection:
    """多層防護交互測試。"""

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
    async def test_reply_plus_timestamp_double_protection(self):
        """has_reference=True 且 embed 有時間戳，雙重保護。"""
        router = self._make_router()
        self.ai_parser.parse.return_value = {"action": "CLOSE", "symbol": "BTCUSDT"}
        self.api_client.send_trade.return_value = MagicMock(success=True, summary="OK")

        msg = _make_msg(
            content="出局了",
            embeds=[{
                "title": "陈哥合约频道 2026-03-03 00:48",
                "description": "BTC 做空 70000",
            }],
            has_reference=True,
            message_id="msg_double_protect",
        )

        await router.handle_message(msg)

        call_args = self.ai_parser.parse.call_args[0][0]
        assert call_args == "出局了"

    @pytest.mark.asyncio
    async def test_only_content_no_embeds_unaffected(self):
        """純 content 訊息（無 embeds）不受任何影響。"""
        router = self._make_router()
        self.ai_parser.parse.return_value = {
            "action": "ENTRY", "symbol": "BTCUSDT", "side": "SHORT",
            "entry_price": 70000, "stop_loss": 71700,
        }
        self.api_client.send_trade.return_value = MagicMock(success=True, summary="OK")

        msg = _make_msg(
            content="BTC，70000附近，做空\n止損預計: 71700",
            embeds=[],
            has_reference=False,
            message_id="msg_pure_content",
        )

        await router.handle_message(msg)

        self.ai_parser.parse.assert_called_once()
        call_args = self.ai_parser.parse.call_args[0][0]
        assert "70000" in call_args
        assert "做空" in call_args
