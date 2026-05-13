"""
Layer 1 capture watchdog — SignalRouter.seconds_since_any_message + heartbeat payload.

設計重點：
- _last_message_time 在 channel filter 之前就更新，因為 capture stall 的定義是
  「CDP 是不是還在送訊息」— 被 channel / guild / author 擋掉的訊息也代表 CDP 還活著。
- 啟動但從未收到任何訊息 → None（不誤報）。
"""
from __future__ import annotations

import time
from unittest.mock import AsyncMock, MagicMock

import pytest

from src.config import DiscordConfig
from src.signal_router import SignalRouter


def _make_msg(
    content: str = "hello",
    channel_id: str = "ch-allowed",
    message_id: str = "m1",
    author_id: str = "author1",
    guild_id: str = "guild1",
) -> dict:
    return {
        "id": message_id,
        "channel_id": channel_id,
        "guild_id": guild_id,
        "author_id": author_id,
        "author_name": "Tester",
        "content": content,
        "embeds": [],
        "timestamp": "2026-05-13T00:00:00",
    }


def _make_router(
    channel_ids: list[str] | None = None,
    guild_ids: list[str] | None = None,
    author_ids: list[str] | None = None,
) -> SignalRouter:
    config = DiscordConfig(
        channel_ids=channel_ids if channel_ids is not None else ["ch-allowed"],
        guild_ids=guild_ids,
        author_ids=author_ids,
        ignore_keywords=[],
    )
    api = MagicMock()
    api.send_signal = AsyncMock()
    api.send_trade = AsyncMock()
    return SignalRouter(config, api, dry_run=True)


class TestSecondsSinceAnyMessage:
    """SignalRouter.seconds_since_any_message() — Layer 1 watchdog."""

    def test_initial_state_is_none(self):
        """剛建好 router、從未呼叫 handle_message → 回傳 None"""
        router = _make_router()
        assert router.seconds_since_any_message() is None

    @pytest.mark.asyncio
    async def test_updates_after_allowed_message(self):
        """收到通過所有 filter 的訊息 → 回傳很小的正數"""
        router = _make_router(["ch-allowed"])

        await router.handle_message(_make_msg(channel_id="ch-allowed"))
        elapsed = router.seconds_since_any_message()

        assert elapsed is not None
        assert 0 <= elapsed < 5.0  # 剛剛才收，應該 <1 秒

    @pytest.mark.asyncio
    async def test_updates_even_when_channel_filtered(self):
        """關鍵設計：被 channel filter 擋下的訊息也算 — capture stall 偵測的是 CDP 死活，
        不是訊號量，所以 filter rejection 不影響。"""
        router = _make_router(channel_ids=["ch-allowed"])

        # 送一條 channel_id 不在白名單的訊息 — handle_message 會在 channel filter 階段 return
        await router.handle_message(_make_msg(channel_id="ch-blocked"))

        elapsed = router.seconds_since_any_message()
        assert elapsed is not None
        assert 0 <= elapsed < 5.0

    @pytest.mark.asyncio
    async def test_updates_even_when_guild_filtered(self):
        """被 guild filter 擋下的訊息也算（一樣代表 CDP 在 fire）。"""
        router = _make_router(
            channel_ids=["ch-allowed"], guild_ids=["guild-only"],
        )
        await router.handle_message(
            _make_msg(channel_id="ch-allowed", guild_id="guild-other"),
        )
        assert router.seconds_since_any_message() is not None

    @pytest.mark.asyncio
    async def test_updates_even_when_author_filtered(self):
        """被 author filter 擋下也算。"""
        router = _make_router(
            channel_ids=["ch-allowed"], author_ids=["only-this-author"],
        )
        await router.handle_message(
            _make_msg(channel_id="ch-allowed", author_id="someone-else"),
        )
        assert router.seconds_since_any_message() is not None

    @pytest.mark.asyncio
    async def test_advances_with_time(self):
        """連續兩次 handle_message，第一次與第二次之間距離應該變大（差約等於 sleep 時間）。"""
        router = _make_router(["ch-allowed"])
        await router.handle_message(_make_msg(channel_id="ch-allowed", message_id="m1"))
        # 不能用真正 time.sleep（測試慢），但可以直接覆寫 _last_message_time 模擬時間流逝
        router._last_message_time = time.time() - 100.0
        elapsed = router.seconds_since_any_message()
        assert elapsed is not None and elapsed >= 99.0


class TestHeartbeatPayloadIncludesSecondsSinceAnyMessage:
    """ApiClient.send_heartbeat 必須在 payload 帶 secondsSinceAnyMessage。"""

    @pytest.mark.asyncio
    async def test_payload_includes_value(self):
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

        await client.send_heartbeat(
            status="connected",
            ai_status="active",
            seconds_since_any_message=42.5,
        )

        call_args = mock_session.post.call_args
        payload = call_args.kwargs.get("json") or call_args[1].get("json")
        assert payload["secondsSinceAnyMessage"] == 42.5

    @pytest.mark.asyncio
    async def test_payload_omits_when_none(self):
        """seconds_since_any_message=None 時 payload 不含該欄位（向後相容）。"""
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

        await client.send_heartbeat(
            status="connected",
            ai_status="active",
            seconds_since_any_message=None,
        )

        call_args = mock_session.post.call_args
        payload = call_args.kwargs.get("json") or call_args[1].get("json")
        assert "secondsSinceAnyMessage" not in payload
