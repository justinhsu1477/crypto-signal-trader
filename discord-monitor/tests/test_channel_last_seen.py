"""
單元測試 — 每頻道最後活動時間追蹤 (channel_last_seen)

測試覆蓋：
1. SignalRouter: handle_message 後 channel_last_seen 記錄時間
2. SignalRouter: 被 channel filter 過濾的訊息不記錄
3. ApiClient: send_heartbeat 帶 channelLastSeen payload
4. ApiClient: send_heartbeat 不帶 channelLastSeen 時 payload 不含該欄位
"""
from __future__ import annotations

import time
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from src.config import DiscordConfig
from src.signal_router import SignalRouter


def _make_msg(
    content: str = "📢 BTCUSDT LONG",
    channel_id: str = "ch123",
    message_id: str = "msg001",
) -> dict:
    return {
        "id": message_id,
        "channel_id": channel_id,
        "guild_id": "guild1",
        "author_id": "author1",
        "author_name": "TestUser",
        "content": content,
        "embeds": [],
        "timestamp": "2025-01-01T00:00:00",
    }


class TestSignalRouterChannelLastSeen:
    """SignalRouter.channel_last_seen 測試"""

    def _make_router(self, channel_ids: list[str] | None = None) -> SignalRouter:
        config = DiscordConfig(
            channel_ids=channel_ids or ["ch123"],
            guild_ids=None,
            author_ids=None,
            ignore_keywords=[],
        )
        api = MagicMock()
        api.send_signal = AsyncMock()
        api.send_trade = AsyncMock()
        return SignalRouter(config, api, dry_run=True)

    @pytest.mark.asyncio
    async def test_records_last_seen_on_message(self):
        """收到訊息後 channel_last_seen 有該頻道的時間"""
        router = self._make_router(["ch123"])

        before = time.time()
        await router.handle_message(_make_msg(channel_id="ch123"))
        after = time.time()

        assert "ch123" in router.channel_last_seen
        assert before <= router.channel_last_seen["ch123"] <= after

    @pytest.mark.asyncio
    async def test_no_record_for_filtered_channel(self):
        """不在 whitelist 的頻道不記錄"""
        router = self._make_router(["ch123"])

        await router.handle_message(_make_msg(channel_id="ch999", message_id="msg002"))

        assert "ch999" not in router.channel_last_seen

    @pytest.mark.asyncio
    async def test_updates_on_new_message(self):
        """同一頻道的新訊息更新 last_seen 時間"""
        router = self._make_router(["ch123"])

        await router.handle_message(_make_msg(channel_id="ch123", message_id="msg001"))
        first_seen = router.channel_last_seen["ch123"]

        # 確保時間戳不同
        await router.handle_message(_make_msg(channel_id="ch123", message_id="msg002"))
        second_seen = router.channel_last_seen["ch123"]

        assert second_seen >= first_seen

    @pytest.mark.asyncio
    async def test_records_even_if_author_filtered(self):
        """即使被 author filter 過濾，也會記錄 channel_last_seen（通過 channel filter 就算活躍）"""
        config = DiscordConfig(
            channel_ids=["ch123"],
            guild_ids=None,
            author_ids=["other_author"],  # 限定只看 other_author
            ignore_keywords=[],
        )
        api = MagicMock()
        router = SignalRouter(config, api, dry_run=True)

        # author_id="author1" 不在 author_ids 白名單
        await router.handle_message(_make_msg(channel_id="ch123"))

        assert "ch123" in router.channel_last_seen

    @pytest.mark.asyncio
    async def test_multiple_channels(self):
        """多個頻道各自記錄"""
        router = self._make_router(["ch1", "ch2", "ch3"])

        await router.handle_message(_make_msg(channel_id="ch1", message_id="m1"))
        await router.handle_message(_make_msg(channel_id="ch3", message_id="m2"))

        assert "ch1" in router.channel_last_seen
        assert "ch3" in router.channel_last_seen
        assert "ch2" not in router.channel_last_seen


class TestApiClientChannelLastSeen:
    """ApiClient.send_heartbeat channelLastSeen payload 測試"""

    @pytest.mark.asyncio
    async def test_heartbeat_includes_channel_last_seen(self):
        """send_heartbeat 帶 channel_last_seen 時 payload 含 channelLastSeen"""
        from src.api_client import ApiClient
        from src.config import ApiConfig

        config = MagicMock(spec=ApiConfig)
        config.base_url = "http://localhost:8080"
        config.timeout = 10
        config.api_key = None

        client = ApiClient(config)
        # Mock session
        mock_resp = AsyncMock()
        mock_resp.status = 200
        mock_resp.__aenter__ = AsyncMock(return_value=mock_resp)
        mock_resp.__aexit__ = AsyncMock(return_value=False)

        mock_session = MagicMock()
        mock_session.post = MagicMock(return_value=mock_resp)
        client._session = mock_session

        channel_data = {"ch1": 1710000000.0, "ch2": 1710000060.5}
        await client.send_heartbeat(
            status="connected",
            ai_status="active",
            channel_last_seen=channel_data,
        )

        # 驗證 POST 的 payload
        call_args = mock_session.post.call_args
        payload = call_args.kwargs.get("json") or call_args[1].get("json")
        assert "channelLastSeen" in payload
        assert payload["channelLastSeen"]["ch1"] == 1710000000000  # 轉為 millis
        assert payload["channelLastSeen"]["ch2"] == 1710000060500

    @pytest.mark.asyncio
    async def test_heartbeat_without_channel_last_seen(self):
        """send_heartbeat 不帶 channel_last_seen 時 payload 不含 channelLastSeen"""
        from src.api_client import ApiClient
        from src.config import ApiConfig

        config = MagicMock(spec=ApiConfig)
        config.base_url = "http://localhost:8080"
        config.timeout = 10
        config.api_key = None

        client = ApiClient(config)
        mock_resp = AsyncMock()
        mock_resp.status = 200
        mock_resp.__aenter__ = AsyncMock(return_value=mock_resp)
        mock_resp.__aexit__ = AsyncMock(return_value=False)

        mock_session = MagicMock()
        mock_session.post = MagicMock(return_value=mock_resp)
        client._session = mock_session

        await client.send_heartbeat(status="connected", ai_status="active")

        call_args = mock_session.post.call_args
        payload = call_args.kwargs.get("json") or call_args[1].get("json")
        assert "channelLastSeen" not in payload
