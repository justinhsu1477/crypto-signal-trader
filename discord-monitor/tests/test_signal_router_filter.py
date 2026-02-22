"""
單元測試 — SignalRouter 內容黑名單過濾

測試覆蓋：
1. 含黑名單關鍵字的訊息被攔截（content + embed）
2. 正常交易訊號不受影響
3. 黑名單為空時全部放行
4. 環境變數覆蓋 config.yml
"""
from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock, MagicMock

import pytest

from src.config import DiscordConfig
from src.signal_router import SignalRouter


def _make_msg(
    content: str = "",
    embeds: list[dict] | None = None,
    channel_id: str = "ch123",
    message_id: str = "msg001",
) -> dict:
    """建構一條 Discord 訊息 dict。"""
    return {
        "id": message_id,
        "channel_id": channel_id,
        "guild_id": "guild1",
        "author_id": "author1",
        "author_name": "TestUser",
        "content": content,
        "embeds": embeds or [],
        "timestamp": "2025-01-01T00:00:00",
    }


class TestIgnoreKeywordsFilter:
    """SignalRouter 內容黑名單過濾測試。"""

    def setup_method(self):
        self.api_client = MagicMock()
        self.api_client.send_signal = AsyncMock()
        self.api_client.send_trade = AsyncMock()
        self.ai_parser = AsyncMock()

    def _make_router(self, ignore_keywords: list[str] | None = None) -> SignalRouter:
        config = DiscordConfig(
            channel_ids=["ch123"],
            ignore_keywords=ignore_keywords or [],
        )
        return SignalRouter(
            discord_config=config,
            api_client=self.api_client,
            dry_run=False,
            ai_parser=self.ai_parser,
        )

    @pytest.mark.asyncio
    async def test_ignore_keyword_skips_message(self):
        """含「一對一」的訊息不應呼叫 AI parser 或 API。"""
        router = self._make_router(ignore_keywords=["一對一"])
        msg = _make_msg(content="一對一指導BTC68500空單 市價67300附近止盈50%成本保護。")

        await router.handle_message(msg)

        self.ai_parser.parse.assert_not_called()
        self.api_client.send_trade.assert_not_called()
        self.api_client.send_signal.assert_not_called()

    @pytest.mark.asyncio
    async def test_ignore_keyword_simplified_chinese(self):
        """簡體「一对一」同樣被攔截。"""
        router = self._make_router(ignore_keywords=["一對一", "一对一"])
        msg = _make_msg(content="一对一带单 BTC做空")

        await router.handle_message(msg)

        self.ai_parser.parse.assert_not_called()

    @pytest.mark.asyncio
    async def test_ignore_keyword_in_embed(self):
        """embed 中含黑名單關鍵字也應被攔截。"""
        router = self._make_router(ignore_keywords=["一對一"])
        msg = _make_msg(
            content="",
            embeds=[{"title": "陳哥合約頻道", "description": "一對一指導BTC68500空單"}],
        )

        await router.handle_message(msg)

        self.ai_parser.parse.assert_not_called()
        self.api_client.send_trade.assert_not_called()

    @pytest.mark.asyncio
    async def test_normal_signal_passes(self):
        """正常交易訊號不應被攔截。"""
        router = self._make_router(ignore_keywords=["一對一", "一对一"])
        self.ai_parser.parse.return_value = {
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "SHORT",
            "entry_price": 68500,
            "stop_loss": 70000,
        }
        self.api_client.send_trade.return_value = MagicMock(success=True, summary="OK")

        msg = _make_msg(content="📢 交易訊號發布: BTCUSDT\n做空 SHORT\n入場價格 68500\n止損 70000")

        await router.handle_message(msg)

        self.ai_parser.parse.assert_called_once()

    @pytest.mark.asyncio
    async def test_empty_ignore_keywords_no_filter(self):
        """黑名單為空時，含任何關鍵字的訊息都應放行。"""
        router = self._make_router(ignore_keywords=[])
        self.ai_parser.parse.return_value = {
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "SHORT",
            "entry_price": 68500,
            "stop_loss": 70000,
        }
        self.api_client.send_trade.return_value = MagicMock(success=True, summary="OK")

        msg = _make_msg(content="一對一指導BTC68500空單")

        await router.handle_message(msg)

        # 黑名單為空，即使含「一對一」也會被處理
        self.ai_parser.parse.assert_called_once()

    @pytest.mark.asyncio
    async def test_multiple_keywords_any_match(self):
        """多個黑名單關鍵字，命中任一即攔截。"""
        router = self._make_router(ignore_keywords=["一對一", "包教包會", "VIP"])
        msg = _make_msg(content="VIP專屬 BTC68500空單")

        await router.handle_message(msg)

        self.ai_parser.parse.assert_not_called()

    @pytest.mark.asyncio
    async def test_keyword_not_matched_passes(self):
        """訊息不含任何黑名單關鍵字時放行。"""
        router = self._make_router(ignore_keywords=["一對一", "包教包會"])
        self.ai_parser.parse.return_value = {
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "SHORT",
            "entry_price": 68500,
            "stop_loss": 70000,
        }
        self.api_client.send_trade.return_value = MagicMock(success=True, summary="OK")

        msg = _make_msg(content="BTC 68500 做空 止損 70000")

        await router.handle_message(msg)

        self.ai_parser.parse.assert_called_once()


class TestIgnoreKeywordsConfig:
    """config.py ignore_keywords 配置測試。"""

    def test_env_var_override(self, monkeypatch):
        """環境變數 DISCORD_IGNORE_KEYWORDS 能覆蓋 config.yml。"""
        from src.config import _env_list

        monkeypatch.setenv("DISCORD_IGNORE_KEYWORDS", "一對一,VIP,包教")
        result = _env_list("DISCORD_IGNORE_KEYWORDS", ["default"])
        assert result == ["一對一", "VIP", "包教"]

    def test_env_var_empty_falls_back(self, monkeypatch):
        """環境變數為空時 fallback 到 YAML 預設值。"""
        from src.config import _env_list

        monkeypatch.delenv("DISCORD_IGNORE_KEYWORDS", raising=False)
        result = _env_list("DISCORD_IGNORE_KEYWORDS", ["一對一", "一对一"])
        assert result == ["一對一", "一对一"]
