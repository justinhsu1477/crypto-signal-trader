"""api_client.send_discord_message — fire-and-forget per-message archive."""
from __future__ import annotations

from unittest.mock import AsyncMock, MagicMock

import pytest

from src.api_client import ApiClient
from src.config import ApiConfig


def _make_client() -> ApiClient:
    config = ApiConfig(
        base_url="http://localhost:8080",
        multi_user_enabled=True,
        api_key="test-key",
    )
    return ApiClient(config)


class _FakeResp:
    def __init__(self, status: int = 200):
        self.status = status

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False


class _FakeSession:
    def __init__(self, status: int = 200, raise_exc: Exception | None = None):
        self.status = status
        self.raise_exc = raise_exc
        self.post_args: tuple | None = None
        self.post_kwargs: dict | None = None

    def post(self, url, **kwargs):
        self.post_args = (url,)
        self.post_kwargs = kwargs
        if self.raise_exc:
            raise self.raise_exc
        return _FakeResp(status=self.status)


@pytest.mark.asyncio
async def test_send_discord_message_postsCorrectPayload():
    """payload 帶完整欄位 → POST /api/discord-messages 含 JSON body."""
    client = _make_client()
    fake = _FakeSession(status=200)
    client._session = fake  # type: ignore[assignment]

    payload = {
        "message_id": "msg-001",
        "channel_id": "ch-1",
        "author_name": "陳哥",
        "content": "BTC 多單",
        "parser_action": "ENTRY",
    }
    await client.send_discord_message(payload)

    assert fake.post_args is not None, "session.post should have been called"
    assert fake.post_args[0].endswith("/api/discord-messages")
    assert fake.post_kwargs is not None
    assert fake.post_kwargs["json"] == payload


@pytest.mark.asyncio
async def test_send_discord_message_swallowsHttpErrors():
    """server 回 4xx/5xx → 不 raise，只 log warning."""
    client = _make_client()
    fake = _FakeSession(status=500)
    client._session = fake  # type: ignore[assignment]

    # 不應 raise
    await client.send_discord_message({"message_id": "msg-err", "channel_id": "ch-1"})


@pytest.mark.asyncio
async def test_send_discord_message_swallowsNetworkErrors():
    """session.post 拋 exception → 吞下，不影響 caller."""
    client = _make_client()
    fake = _FakeSession(raise_exc=ConnectionError("network down"))
    client._session = fake  # type: ignore[assignment]

    # 不應 raise
    await client.send_discord_message({"message_id": "msg-net", "channel_id": "ch-1"})
