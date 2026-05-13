"""SignalRouter — per-message archive (send_discord_message) 整合測試。

覆蓋：
1. 成功 AI parse → archive 帶正確 parser_action
2. AI 回 INFO → archive 帶 INFO + parser_skipped_reason=None
3. message_id 已處理過（dedup）→ archive 帶 DEDUP_MESSAGE_ID
4. channel filter 攔截 → archive 不被呼叫
"""
from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock, MagicMock

import pytest

from src.config import DiscordConfig
from src.signal_router import SignalRouter


def _make_msg(
    content: str = "BTC 多單 60000",
    channel_id: str = "ch-1",
    message_id: str = "msg-001",
) -> dict:
    return {
        "id": message_id,
        "channel_id": channel_id,
        "guild_id": "guild1",
        "author_id": "author1",
        "author_name": "TestAuthor",
        "channel_name": "vip",
        "content": content,
        "embeds": [],
        "attachments": [],
        "embed_images": [],
        "has_reference": False,
        "has_snapshots": False,
        "timestamp": "2026-05-11T10:00:00",
    }


class TestArchiveCalls:

    def setup_method(self):
        self.api_client = MagicMock()
        self.api_client.send_signal = AsyncMock()
        self.api_client.send_trade = AsyncMock()
        self.api_client.send_trade.return_value = MagicMock(success=True, summary="OK", status_code=200)
        self.api_client.send_discord_message = AsyncMock()
        self.api_client.append_analyst_message = AsyncMock(return_value=True)
        self.ai_parser = AsyncMock()
        # prompt_version is a regular attribute, not async
        self.ai_parser.prompt_version = 0

    def _make_router(self, channel_ids: list[str] | None = None) -> SignalRouter:
        config = DiscordConfig(
            channel_ids=channel_ids if channel_ids is not None else ["ch-1"],
            ignore_keywords=[],
        )
        return SignalRouter(
            discord_config=config,
            api_client=self.api_client,
            dry_run=False,
            ai_parser=self.ai_parser,
        )

    async def _wait_for_tasks(self):
        """讓 asyncio.create_task() 派出的 fire-and-forget tasks 跑完。"""
        # 收集所有 pending tasks（除了當前 task）
        current = asyncio.current_task()
        tasks = [t for t in asyncio.all_tasks() if t is not current]
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    @pytest.mark.asyncio
    async def test_archive_called_after_successful_parse(self):
        """AI 解析成功 → archive 帶 parser_action=ENTRY."""
        router = self._make_router()
        self.ai_parser.parse.return_value = {
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "LONG",
            "entry_price": 60000,
            "stop_loss": 58000,
        }

        msg = _make_msg(content="BTC long 60000 SL 58000")
        await router.handle_message(msg)
        await self._wait_for_tasks()

        # send_discord_message 至少被呼叫一次
        assert self.api_client.send_discord_message.await_count >= 1
        # 找到實際 archive 呼叫的 payload
        last_payload = self.api_client.send_discord_message.await_args.args[0]
        assert last_payload["message_id"] == "msg-001"
        assert last_payload["channel_id"] == "ch-1"
        assert last_payload["parser_action"] == "ENTRY"
        assert last_payload["parser_skipped_reason"] is None
        assert last_payload["content"] == "BTC long 60000 SL 58000"

    @pytest.mark.asyncio
    async def test_archive_called_with_INFO_when_ai_returns_INFO(self):
        """AI 判 INFO → archive 帶 parser_action=INFO."""
        router = self._make_router()
        self.ai_parser.parse.return_value = {
            "action": "INFO",
            "symbol": "BTCUSDT",
        }

        msg = _make_msg(content="今天市場很複雜 注意風險")
        await router.handle_message(msg)
        await self._wait_for_tasks()

        assert self.api_client.send_discord_message.await_count >= 1
        last_payload = self.api_client.send_discord_message.await_args.args[0]
        assert last_payload["parser_action"] == "INFO"
        assert last_payload["parser_skipped_reason"] is None

    @pytest.mark.asyncio
    async def test_archive_called_with_DEDUP_when_message_id_seen(self):
        """同 message_id 第二次 → archive 帶 parser_skipped_reason=DEDUP_MESSAGE_ID."""
        router = self._make_router()
        self.ai_parser.parse.return_value = {
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "LONG",
            "entry_price": 60000,
            "stop_loss": 58000,
        }

        msg = _make_msg(content="BTC long 60000")
        # 第一次
        await router.handle_message(msg)
        await self._wait_for_tasks()

        first_call_count = self.api_client.send_discord_message.await_count

        # 第二次相同 message_id
        await router.handle_message(msg)
        await self._wait_for_tasks()

        # 應該又被呼叫一次（dedup 路徑也 archive）
        assert self.api_client.send_discord_message.await_count > first_call_count
        last_payload = self.api_client.send_discord_message.await_args.args[0]
        assert last_payload["parser_skipped_reason"] == "DEDUP_MESSAGE_ID"

    @pytest.mark.asyncio
    async def test_archive_not_called_when_channel_filter_blocks(self):
        """頻道白名單未通過 → archive 完全不被呼叫（早期 return）."""
        router = self._make_router(channel_ids=["ch-allowed-only"])
        msg = _make_msg(channel_id="ch-blocked")

        await router.handle_message(msg)
        await self._wait_for_tasks()

        self.api_client.send_discord_message.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_archive_called_with_BLACKLIST_when_ignore_kw_hits(self):
        """命中黑名單關鍵字 → archive 帶 parser_skipped_reason=BLACKLIST."""
        config = DiscordConfig(channel_ids=["ch-1"], ignore_keywords=["一對一"])
        router = SignalRouter(
            discord_config=config,
            api_client=self.api_client,
            dry_run=False,
            ai_parser=self.ai_parser,
        )
        msg = _make_msg(content="一對一指導 BTC 多單")
        await router.handle_message(msg)
        await self._wait_for_tasks()

        assert self.api_client.send_discord_message.await_count >= 1
        last_payload = self.api_client.send_discord_message.await_args.args[0]
        assert last_payload["parser_skipped_reason"] == "BLACKLIST"
