"""
MESSAGE_UPDATE 訂閱 + 編輯訊號處理。

設計要點：
- CDP JS hook 訂閱 MESSAGE_UPDATE，data dict 多帶 is_edit=True + edit_revision。
- signal_router 在收到 is_edit=True 時：
    * source["message_id"] 加上 __edit-NNNNNN 後綴（N = revision 末 6 碼）
    * archive 的 parser_action 加 EDIT: 前綴（例：ENTRY → EDIT:ENTRY）
- 原始 message_id（未編輯版）行為完全不變 — 向後相容。
"""
from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock, MagicMock

import pytest

from src.cdp_client import INJECT_JS
from src.config import DiscordConfig
from src.signal_router import SignalRouter


def _make_msg(
    content: str = "BTC 60000 long SL 58000",
    channel_id: str = "ch-1",
    message_id: str = "orig-msg-001",
    is_edit: bool = False,
    edit_revision: int | None = None,
) -> dict:
    msg = {
        "id": message_id,
        "channel_id": channel_id,
        "guild_id": "guild1",
        "author_id": "author1",
        "author_name": "Tester",
        "channel_name": "vip",
        "content": content,
        "embeds": [],
        "attachments": [],
        "embed_images": [],
        "has_reference": False,
        "has_snapshots": False,
        "timestamp": "2026-05-13T10:00:00",
    }
    if is_edit:
        msg["is_edit"] = True
        msg["edit_revision"] = edit_revision if edit_revision is not None else 1715600123456
    return msg


class TestInjectJsSubscribesMessageUpdate:
    """CDP INJECT_JS 必須訂閱 MESSAGE_UPDATE + 設正確旗標。"""

    def test_inject_js_subscribes_message_update(self):
        """INJECT_JS 必須含 MESSAGE_UPDATE 訂閱。"""
        assert "MESSAGE_UPDATE" in INJECT_JS

    def test_inject_js_sets_is_edit_flag(self):
        """訂閱 handler 必須塞 is_edit=true / edit_revision 進 data dict。"""
        assert "is_edit" in INJECT_JS
        assert "edit_revision" in INJECT_JS

    def test_inject_js_still_subscribes_create(self):
        """MESSAGE_CREATE 訂閱不能被誤刪。"""
        assert "MESSAGE_CREATE" in INJECT_JS

    def test_inject_js_shares_builder(self):
        """共用 buildMessageData — 避免 CREATE/UPDATE 30+ 行欄位重複（DRY check）。"""
        assert "buildMessageData" in INJECT_JS


class TestMessageUpdateSuffix:
    """signal_router.handle_message 對編輯訊號的處理。"""

    def setup_method(self):
        self.api_client = MagicMock()
        self.api_client.send_signal = AsyncMock()
        self.api_client.send_trade = AsyncMock()
        self.api_client.send_trade.return_value = MagicMock(
            success=True, summary="OK", status_code=200,
        )
        self.api_client.send_discord_message = AsyncMock()
        self.api_client.append_analyst_message = AsyncMock(return_value=True)
        self.ai_parser = AsyncMock()
        self.ai_parser.prompt_version = 0
        self.ai_parser.parse = AsyncMock(return_value={
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "LONG",
            "entry_price": 60000,
            "stop_loss": 58000,
        })

    def _make_router(self) -> SignalRouter:
        config = DiscordConfig(channel_ids=["ch-1"], ignore_keywords=[])
        return SignalRouter(
            discord_config=config,
            api_client=self.api_client,
            dry_run=False,
            ai_parser=self.ai_parser,
        )

    async def _wait_for_tasks(self):
        """讓 fire-and-forget tasks 執行完。"""
        current = asyncio.current_task()
        tasks = [t for t in asyncio.all_tasks() if t is not current]
        if tasks:
            await asyncio.gather(*tasks, return_exceptions=True)

    @pytest.mark.asyncio
    async def test_message_update_treated_as_new_msg_id(self):
        """is_edit=True 訊息 → source.message_id 帶 __edit-NNNNNN 後綴避開 Java L1 dedup。"""
        router = self._make_router()

        msg = _make_msg(
            content="BTC 60000 long SL 58000",
            message_id="orig-1",
            is_edit=True,
            edit_revision=1715600123456,
        )
        await router.handle_message(msg)
        await self._wait_for_tasks()

        # send_trade 被呼叫，source.message_id 必須帶 __edit-XXXXXX 後綴
        assert self.api_client.send_trade.await_count == 1
        call_kwargs = self.api_client.send_trade.await_args.kwargs
        source = call_kwargs.get("source") or {}
        # edit_revision=1715600123456 → 末 6 碼 = "123456"
        assert source["message_id"] == "orig-1__edit-123456"

    @pytest.mark.asyncio
    async def test_message_update_preserved_in_archive(self):
        """archive 的 parser_action 加 EDIT: 前綴，message_id 也帶後綴。"""
        router = self._make_router()

        msg = _make_msg(
            content="BTC 60000 long SL 58000",
            message_id="orig-2",
            is_edit=True,
            edit_revision=999999,
        )
        await router.handle_message(msg)
        await self._wait_for_tasks()

        # 找出 archive payload（send_discord_message 至少一次）
        assert self.api_client.send_discord_message.await_count >= 1
        archive_payload = self.api_client.send_discord_message.await_args.args[0]
        # archive 的 message_id 也帶 __edit- 後綴（每個編輯版本各自有 audit row）
        assert archive_payload["message_id"] == "orig-2__edit-999999"
        # parser_action 加 EDIT: 前綴
        assert archive_payload["parser_action"] == "EDIT:ENTRY"

    @pytest.mark.asyncio
    async def test_message_create_no_edit_flag(self):
        """原始 MESSAGE_CREATE 訊息（無 is_edit）— 行為完全不變（向後相容）。"""
        router = self._make_router()

        msg = _make_msg(
            content="BTC 60000 long SL 58000",
            message_id="msg-create",
            is_edit=False,
        )
        await router.handle_message(msg)
        await self._wait_for_tasks()

        # source.message_id 不帶後綴
        call_kwargs = self.api_client.send_trade.await_args.kwargs
        source = call_kwargs.get("source") or {}
        assert source["message_id"] == "msg-create"

        # archive parser_action 不加 EDIT 前綴
        archive_payload = self.api_client.send_discord_message.await_args.args[0]
        assert archive_payload["parser_action"] == "ENTRY"
        assert archive_payload["message_id"] == "msg-create"

    @pytest.mark.asyncio
    async def test_message_update_with_zero_revision_falls_back(self):
        """edit_revision 缺失 / 為 0 時用 '000000' 預設後綴，不爆掉。"""
        router = self._make_router()

        msg = _make_msg(
            content="BTC 60000 long SL 58000",
            message_id="orig-no-rev",
            is_edit=True,
            edit_revision=0,
        )
        await router.handle_message(msg)
        await self._wait_for_tasks()

        call_kwargs = self.api_client.send_trade.await_args.kwargs
        source = call_kwargs.get("source") or {}
        assert source["message_id"] == "orig-no-rev__edit-000000"

    @pytest.mark.asyncio
    async def test_multiple_edits_each_get_unique_message_id(self):
        """同訊息編輯兩次（不同 revision）→ 各自有獨立 message_id，Java 視為兩筆。"""
        router = self._make_router()

        await router.handle_message(_make_msg(
            content="BTC 60000 long SL 58000",
            message_id="orig-multi",
            is_edit=True,
            edit_revision=111111,
        ))
        await router.handle_message(_make_msg(
            content="BTC 60500 long SL 58500",
            message_id="orig-multi",
            is_edit=True,
            edit_revision=222222,
        ))
        await self._wait_for_tasks()

        # 兩次 send_trade，suffix 不同
        assert self.api_client.send_trade.await_count == 2
        first_msg_id = self.api_client.send_trade.await_args_list[0].kwargs["source"]["message_id"]
        second_msg_id = self.api_client.send_trade.await_args_list[1].kwargs["source"]["message_id"]
        assert first_msg_id == "orig-multi__edit-111111"
        assert second_msg_id == "orig-multi__edit-222222"
