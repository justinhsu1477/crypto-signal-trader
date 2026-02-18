"""Tests for AiSignalParser retry logic on Gemini 429 rate limits."""
from __future__ import annotations

import json
import pytest
from unittest.mock import AsyncMock, MagicMock, patch, PropertyMock

from src.ai_parser import AiSignalParser
from src.config import AiConfig


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_config(**overrides) -> AiConfig:
    """Create an AiConfig with sensible test defaults."""
    defaults = dict(
        enabled=True,
        model="gemini-2.0-flash",
        api_key_env="GEMINI_API_KEY",
        timeout=15,
        max_retries=3,
        retry_delays=[2, 5, 10],
    )
    defaults.update(overrides)
    return AiConfig(**defaults)


def _make_parser(config: AiConfig | None = None) -> AiSignalParser:
    """Create an AiSignalParser with a mocked genai client."""
    config = config or _make_config()
    with patch.dict("os.environ", {"GEMINI_API_KEY": "fake-key"}):
        parser = AiSignalParser(config)
    # Replace real genai client with mock
    parser.client = MagicMock()
    return parser


def _mock_success_response(data: dict) -> AsyncMock:
    """Create a mock Gemini response returning valid JSON."""
    response = AsyncMock()
    type(response).text = PropertyMock(return_value=json.dumps(data))
    return response


VALID_ENTRY = {
    "action": "ENTRY",
    "symbol": "BTCUSDT",
    "side": "SHORT",
    "entry_price": 70900,
    "stop_loss": 72000,
}

VALID_INFO = {
    "action": "INFO",
}


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class TestParseRetry:
    """AI parser retry logic tests."""

    @pytest.mark.asyncio
    async def test_parse_success_first_attempt(self):
        """First attempt succeeds — no retry triggered."""
        parser = _make_parser()
        parser.client.aio.models.generate_content = AsyncMock(
            return_value=_mock_success_response(VALID_ENTRY)
        )

        result = await parser.parse("BTC 70900 做空 止損72000")

        assert result is not None
        assert result["action"] == "ENTRY"
        assert result["symbol"] == "BTCUSDT"
        assert parser.client.aio.models.generate_content.call_count == 1

    @pytest.mark.asyncio
    async def test_rate_limit_retry_then_success(self):
        """First attempt 429, second attempt succeeds."""
        parser = _make_parser()
        parser.client.aio.models.generate_content = AsyncMock(
            side_effect=[
                Exception("429 RESOURCE_EXHAUSTED. Please try again later."),
                _mock_success_response(VALID_ENTRY),
            ]
        )

        with patch("src.ai_parser.asyncio.sleep", new_callable=AsyncMock) as mock_sleep:
            result = await parser.parse("BTC 70900 做空 止損72000")

        assert result is not None
        assert result["action"] == "ENTRY"
        assert parser.client.aio.models.generate_content.call_count == 2
        # Should have slept with first retry delay
        mock_sleep.assert_called_once_with(2)

    @pytest.mark.asyncio
    async def test_rate_limit_all_retries_exhausted(self):
        """All 3 attempts hit 429 — returns None."""
        parser = _make_parser()
        parser.client.aio.models.generate_content = AsyncMock(
            side_effect=Exception("429 RESOURCE_EXHAUSTED")
        )

        with patch("src.ai_parser.asyncio.sleep", new_callable=AsyncMock) as mock_sleep:
            result = await parser.parse("BTC 70900 做空")

        assert result is None
        assert parser.client.aio.models.generate_content.call_count == 3
        # Should have slept twice (not on last attempt)
        assert mock_sleep.call_count == 2
        mock_sleep.assert_any_call(2)   # first retry delay
        mock_sleep.assert_any_call(5)   # second retry delay

    @pytest.mark.asyncio
    async def test_json_error_no_retry(self):
        """JSON decode error — returns None immediately, no retry."""
        parser = _make_parser()
        bad_response = AsyncMock()
        type(bad_response).text = PropertyMock(return_value="not valid json {{{")
        parser.client.aio.models.generate_content = AsyncMock(
            return_value=bad_response
        )

        result = await parser.parse("some signal")

        assert result is None
        # Should only be called once (no retry for JSON errors)
        assert parser.client.aio.models.generate_content.call_count == 1

    @pytest.mark.asyncio
    async def test_non_rate_limit_error_no_retry(self):
        """Non-429 exception — returns None immediately, no retry."""
        parser = _make_parser()
        parser.client.aio.models.generate_content = AsyncMock(
            side_effect=Exception("Connection timeout")
        )

        result = await parser.parse("BTC 70900 做空")

        assert result is None
        # Should only be called once (no retry for non-429)
        assert parser.client.aio.models.generate_content.call_count == 1

    @pytest.mark.asyncio
    async def test_retry_respects_config_delays(self):
        """Retry delays should come from config.retry_delays."""
        config = _make_config(max_retries=4, retry_delays=[1, 3, 7, 15])
        parser = _make_parser(config)
        parser.client.aio.models.generate_content = AsyncMock(
            side_effect=Exception("429 RESOURCE_EXHAUSTED")
        )

        with patch("src.ai_parser.asyncio.sleep", new_callable=AsyncMock) as mock_sleep:
            result = await parser.parse("test signal")

        assert result is None
        assert parser.client.aio.models.generate_content.call_count == 4
        # 3 sleeps (not on last attempt)
        assert mock_sleep.call_count == 3
        mock_sleep.assert_any_call(1)   # retry_delays[0]
        mock_sleep.assert_any_call(3)   # retry_delays[1]
        mock_sleep.assert_any_call(7)   # retry_delays[2]

    @pytest.mark.asyncio
    async def test_parse_disabled_returns_none(self):
        """When client is None (no API key), returns None without retry."""
        config = _make_config()
        with patch.dict("os.environ", {"GEMINI_API_KEY": ""}):
            parser = AiSignalParser(config)

        assert parser.client is None
        result = await parser.parse("BTC 70900 做空")
        assert result is None

    @pytest.mark.asyncio
    async def test_rate_limit_then_json_error(self):
        """First attempt 429, second attempt returns bad JSON — no further retry."""
        parser = _make_parser()
        bad_response = AsyncMock()
        type(bad_response).text = PropertyMock(return_value="invalid json!")
        parser.client.aio.models.generate_content = AsyncMock(
            side_effect=[
                Exception("429 RESOURCE_EXHAUSTED"),
                bad_response,  # returns response but text is not valid JSON
            ]
        )

        with patch("src.ai_parser.asyncio.sleep", new_callable=AsyncMock):
            result = await parser.parse("test signal")

        assert result is None
        assert parser.client.aio.models.generate_content.call_count == 2

    @pytest.mark.asyncio
    async def test_resource_exhausted_string_match(self):
        """'RESOURCE_EXHAUSTED' in error message (without 429) also triggers retry."""
        parser = _make_parser()
        parser.client.aio.models.generate_content = AsyncMock(
            side_effect=[
                Exception("RESOURCE_EXHAUSTED: quota exceeded"),
                _mock_success_response(VALID_ENTRY),
            ]
        )

        with patch("src.ai_parser.asyncio.sleep", new_callable=AsyncMock):
            result = await parser.parse("BTC 70900 做空 止損72000")

        assert result is not None
        assert result["action"] == "ENTRY"
        assert parser.client.aio.models.generate_content.call_count == 2

    @pytest.mark.asyncio
    async def test_info_action_returns_correctly(self):
        """INFO action should be parsed and returned (not treated as error)."""
        parser = _make_parser()
        parser.client.aio.models.generate_content = AsyncMock(
            return_value=_mock_success_response(VALID_INFO)
        )

        result = await parser.parse("大家晚安")

        assert result is not None
        assert result["action"] == "INFO"
        assert parser.client.aio.models.generate_content.call_count == 1

    @pytest.mark.asyncio
    async def test_retry_delay_index_capped(self):
        """When retries > len(retry_delays), uses last delay value."""
        config = _make_config(max_retries=5, retry_delays=[1, 3])
        parser = _make_parser(config)
        parser.client.aio.models.generate_content = AsyncMock(
            side_effect=Exception("429 RESOURCE_EXHAUSTED")
        )

        with patch("src.ai_parser.asyncio.sleep", new_callable=AsyncMock) as mock_sleep:
            result = await parser.parse("test")

        assert result is None
        assert parser.client.aio.models.generate_content.call_count == 5
        # 4 sleeps: delays[0]=1, delays[1]=3, delays[1]=3, delays[1]=3
        assert mock_sleep.call_count == 4
        calls = [c.args[0] for c in mock_sleep.call_args_list]
        assert calls == [1, 3, 3, 3]
