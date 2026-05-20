"""Heartbeat payload 行為合約測試。"""
from __future__ import annotations

import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from src.api_client import ApiClient
from src.config import ApiConfig


@pytest.mark.asyncio
async def test_send_heartbeat_includes_monitor_version_when_provided():
    """Python 啟動時讀 git HEAD → 傳 monitor_version → 走進 payload。"""
    captured_json = {}

    class FakeResp:
        status = 200
        async def __aenter__(self): return self
        async def __aexit__(self, *args): return None
        async def text(self): return ""

    def fake_post(url, json, timeout):
        captured_json.update(json)
        return FakeResp()

    config = ApiConfig(base_url="http://test", api_key="k")
    client = ApiClient(config)
    client._session = MagicMock()
    client._session.post = MagicMock(side_effect=fake_post)

    await client.send_heartbeat(
        status="connected",
        ai_status="active",
        monitor_version="abc1234",
    )

    assert captured_json.get("monitorVersion") == "abc1234"


@pytest.mark.asyncio
async def test_send_heartbeat_omits_monitor_version_when_none():
    """沒提供 monitor_version → payload 沒這個欄位（向下相容老 Python）。"""
    captured_json = {}

    class FakeResp:
        status = 200
        async def __aenter__(self): return self
        async def __aexit__(self, *args): return None
        async def text(self): return ""

    def fake_post(url, json, timeout):
        captured_json.update(json)
        return FakeResp()

    config = ApiConfig(base_url="http://test", api_key="k")
    client = ApiClient(config)
    client._session = MagicMock()
    client._session.post = MagicMock(side_effect=fake_post)

    await client.send_heartbeat(status="connected", ai_status="active")

    assert "monitorVersion" not in captured_json
