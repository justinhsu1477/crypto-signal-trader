"""Tests for ApiClient retry logic."""

import asyncio
import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from aiohttp import ClientResponseError, ClientConnectorError
from src.api_client import ApiClient, ExecutionResult, MAX_RETRIES

# Minimal config mock
class FakeConfig:
    base_url = "http://localhost:8080"
    execute_endpoint = "/api/execute-signal"
    parse_endpoint = "/api/parse-signal"
    timeout = 10


@pytest.fixture
def client():
    c = ApiClient(FakeConfig())
    c._session = MagicMock()
    return c


class TestPostWithRetry:
    """_post_with_retry() retry logic tests."""

    @pytest.mark.asyncio
    async def test_success_on_first_attempt(self, client):
        """200 response — no retry needed."""
        mock_resp = AsyncMock()
        mock_resp.status = 200
        mock_resp.json = AsyncMock(return_value={"result": "ok"})
        mock_resp.__aenter__ = AsyncMock(return_value=mock_resp)
        mock_resp.__aexit__ = AsyncMock(return_value=False)
        client._session.post = MagicMock(return_value=mock_resp)

        result = await client._post_with_retry("http://test/api", {"key": "val"})

        assert result.success is True
        assert result.status_code == 200

    @pytest.mark.asyncio
    async def test_4xx_no_retry(self, client):
        """4xx client error — should NOT retry."""
        mock_resp = AsyncMock()
        mock_resp.status = 400
        mock_resp.json = AsyncMock(return_value={"error": "bad request"})
        mock_resp.__aenter__ = AsyncMock(return_value=mock_resp)
        mock_resp.__aexit__ = AsyncMock(return_value=False)
        client._session.post = MagicMock(return_value=mock_resp)

        result = await client._post_with_retry("http://test/api", {})

        assert result.success is False
        assert result.status_code == 400
        # Should only be called once (no retry for 4xx)
        assert client._session.post.call_count == 1

    @pytest.mark.asyncio
    async def test_5xx_retries_then_fails(self, client):
        """5xx server error — should retry MAX_RETRIES times then fail."""
        mock_resp = AsyncMock()
        mock_resp.status = 503
        mock_resp.json = AsyncMock(return_value={"error": "service unavailable"})
        mock_resp.__aenter__ = AsyncMock(return_value=mock_resp)
        mock_resp.__aexit__ = AsyncMock(return_value=False)
        client._session.post = MagicMock(return_value=mock_resp)

        # Patch sleep to avoid actual delays in tests
        with patch("src.api_client.asyncio.sleep", new_callable=AsyncMock):
            result = await client._post_with_retry("http://test/api", {})

        assert result.success is False
        assert "All 3 retries failed" in result.error
        assert client._session.post.call_count == MAX_RETRIES

    @pytest.mark.asyncio
    async def test_network_error_retries(self, client):
        """Network exception — should retry."""
        client._session.post = MagicMock(
            side_effect=Exception("Connection refused")
        )

        with patch("src.api_client.asyncio.sleep", new_callable=AsyncMock):
            result = await client._post_with_retry("http://test/api", {})

        assert result.success is False
        assert "All 3 retries failed" in result.error
        assert client._session.post.call_count == MAX_RETRIES

    @pytest.mark.asyncio
    async def test_5xx_then_success(self, client):
        """First attempt 5xx, second attempt success — should succeed."""
        # First call: 503
        fail_resp = AsyncMock()
        fail_resp.status = 503
        fail_resp.json = AsyncMock(return_value={"error": "down"})
        fail_resp.__aenter__ = AsyncMock(return_value=fail_resp)
        fail_resp.__aexit__ = AsyncMock(return_value=False)

        # Second call: 200
        ok_resp = AsyncMock()
        ok_resp.status = 200
        ok_resp.json = AsyncMock(return_value={"result": "ok"})
        ok_resp.__aenter__ = AsyncMock(return_value=ok_resp)
        ok_resp.__aexit__ = AsyncMock(return_value=False)

        client._session.post = MagicMock(side_effect=[fail_resp, ok_resp])

        with patch("src.api_client.asyncio.sleep", new_callable=AsyncMock):
            result = await client._post_with_retry("http://test/api", {})

        assert result.success is True
        assert result.status_code == 200
        assert client._session.post.call_count == 2


class TestSendSignalRetry:
    """send_signal() uses _post_with_retry."""

    @pytest.mark.asyncio
    async def test_send_signal_uses_retry(self, client):
        """send_signal should go through _post_with_retry."""
        mock_resp = AsyncMock()
        mock_resp.status = 200
        mock_resp.json = AsyncMock(return_value={"parsed": True})
        mock_resp.__aenter__ = AsyncMock(return_value=mock_resp)
        mock_resp.__aexit__ = AsyncMock(return_value=False)
        client._session.post = MagicMock(return_value=mock_resp)

        result = await client.send_signal("test message")

        assert result.success is True
        client._session.post.assert_called_once()

    @pytest.mark.asyncio
    async def test_send_signal_dry_run_uses_parse_endpoint(self, client):
        """dry_run=True should use parse endpoint."""
        mock_resp = AsyncMock()
        mock_resp.status = 200
        mock_resp.json = AsyncMock(return_value={"parsed": True})
        mock_resp.__aenter__ = AsyncMock(return_value=mock_resp)
        mock_resp.__aexit__ = AsyncMock(return_value=False)
        client._session.post = MagicMock(return_value=mock_resp)

        result = await client.send_signal("test", dry_run=True)

        assert result.success is True
        call_url = client._session.post.call_args[0][0]
        assert "/api/parse-signal" in call_url


class TestSendTradeRetry:
    """send_trade() uses _post_with_retry."""

    @pytest.mark.asyncio
    async def test_send_trade_dry_run(self, client):
        """dry_run=True should NOT make any HTTP call."""
        result = await client.send_trade({"action": "ENTRY"}, dry_run=True)

        assert result.success is True
        assert "[DRY RUN]" in result.summary

    @pytest.mark.asyncio
    async def test_send_trade_uses_retry(self, client):
        """send_trade should go through _post_with_retry."""
        mock_resp = AsyncMock()
        mock_resp.status = 200
        mock_resp.json = AsyncMock(return_value={"ok": True})
        mock_resp.__aenter__ = AsyncMock(return_value=mock_resp)
        mock_resp.__aexit__ = AsyncMock(return_value=False)
        client._session.post = MagicMock(return_value=mock_resp)

        result = await client.send_trade({"action": "ENTRY", "symbol": "BTCUSDT"})

        assert result.success is True
        call_url = client._session.post.call_args[0][0]
        assert "/api/execute-trade" in call_url
