"""驗證 api_client.send_trade 能正確帶 source.attachment 欄位到 Java。

Java 端 Spring/Jackson 預設 ignoreUnknownProperties=true，所以多帶欄位安全。
這個測試確保 Python 端不會在序列化時 strip 掉 attachment。
"""
from __future__ import annotations

import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from src.api_client import ApiClient
from src.config import ApiConfig


@pytest.mark.asyncio
async def test_send_trade_includes_attachment_in_source():
    """source 帶 attachment 時，HTTP payload 必須含完整 attachment 欄位。"""
    config = ApiConfig(
        base_url="http://localhost:8080",
        multi_user_enabled=True,
        api_key="test-key",
    )
    captured_payload = {}

    async def fake_post_with_retry(self, url, payload):
        captured_payload.update(payload)
        from src.api_client import ExecutionResult
        return ExecutionResult(success=True, status_code=200, summary="ok")

    with patch.object(ApiClient, "_post_with_retry", new=fake_post_with_retry):
        client = ApiClient(config)
        await client.send_trade(
            trade_request={
                "action": "ENTRY",
                "symbol": "BTCUSDT",
                "side": "SHORT",
                "entry_price": 82800,
                "stop_loss": 84500,
            },
            dry_run=False,
            source={
                "platform": "DISCORD",
                "channel_id": "ch_chen",
                "message_id": "msg_xyz",
                "attachment": {
                    "url": "https://cdn.discordapp.com/x.png",
                    "filename": "signal.png",
                    "content_type": "image/png",
                    "sha256": "abc123def456",
                    "size": 200000,
                },
            },
        )

    assert "source" in captured_payload
    assert "attachment" in captured_payload["source"]
    assert captured_payload["source"]["attachment"]["sha256"] == "abc123def456"
    assert captured_payload["source"]["attachment"]["url"] == "https://cdn.discordapp.com/x.png"
    # signal_timestamp 必須仍被加（既有行為）
    assert "signal_timestamp" in captured_payload
