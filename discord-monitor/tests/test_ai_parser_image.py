"""AiSignalParser.parse_with_image — multimodal Gemini 訊號解析測試。"""
from __future__ import annotations

import json
import os
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from src.ai_parser import AiSignalParser
from src.config import AiConfig


def _make_parser_with_mock_client(response_json: dict | str) -> AiSignalParser:
    """建構帶 mock Gemini client 的 AiSignalParser。"""
    os.environ["GEMINI_API_KEY"] = "fake-key-for-test"
    config = AiConfig(enabled=True, model="gemini-2.0-flash", api_key_env="GEMINI_API_KEY")
    parser = AiSignalParser(config)

    mock_response = MagicMock()
    if isinstance(response_json, dict):
        mock_response.text = json.dumps(response_json)
    else:
        mock_response.text = response_json
    mock_response.usage_metadata = MagicMock(
        prompt_token_count=500,
        candidates_token_count=50,
        total_token_count=550,
    )

    mock_models = MagicMock()
    mock_models.generate_content = AsyncMock(return_value=mock_response)

    mock_aio = MagicMock()
    mock_aio.models = mock_models

    mock_client = MagicMock()
    mock_client.aio = mock_aio
    parser.client = mock_client

    return parser


@pytest.mark.asyncio
async def test_parse_with_image_btc_short_signal():
    """陳哥的紫色 BTC SHORT 訊號圖 → 完整 JSON。"""
    parser = _make_parser_with_mock_client({
        "action": "ENTRY",
        "symbol": "BTCUSDT",
        "side": "SHORT",
        "entry_price": 82800,
        "stop_loss": 84500,
        "take_profit": 80800,
    })
    fake_image = b"\x89PNG\r\n\x1a\n" + b"FAKE_BTC_SIGNAL_IMAGE"

    result = await parser.parse_with_image(
        text_content="BTC市价82600-83000附近做空",
        image_bytes=fake_image,
        mime_type="image/png",
    )

    assert result is not None
    assert result["action"] == "ENTRY"
    assert result["symbol"] == "BTCUSDT"
    assert result["side"] == "SHORT"
    assert result["stop_loss"] == 84500


@pytest.mark.asyncio
async def test_parse_with_image_no_text_only_image():
    """純圖無文字 → text_content 傳空字串應該照樣能解析。"""
    parser = _make_parser_with_mock_client({
        "action": "ENTRY",
        "symbol": "BTCUSDT",
        "side": "SHORT",
        "entry_price": 82800,
        "stop_loss": 84500,
    })
    fake_image = b"\x89PNG\r\n\x1a\n" + b"PURE_IMAGE"

    result = await parser.parse_with_image(
        text_content="",
        image_bytes=fake_image,
        mime_type="image/png",
    )

    assert result is not None
    assert result["symbol"] == "BTCUSDT"


@pytest.mark.asyncio
async def test_parse_with_image_returns_none_on_invalid_json():
    """Gemini 回非法 JSON → 回 None（與 parse() 行為一致）。"""
    parser = _make_parser_with_mock_client("this is not json at all")
    fake_image = b"\x89PNG\r\n\x1a\n"

    result = await parser.parse_with_image(
        text_content="",
        image_bytes=fake_image,
        mime_type="image/png",
    )

    assert result is None


@pytest.mark.asyncio
async def test_parse_with_image_token_stats_tracked():
    """Image parse 也要計入 token_stats（heartbeat 看得到）。"""
    parser = _make_parser_with_mock_client({
        "action": "ENTRY", "symbol": "BTCUSDT", "side": "SHORT",
        "entry_price": 82800, "stop_loss": 84500,
    })
    initial_calls = parser.get_token_stats()["call_count"]

    await parser.parse_with_image(
        text_content="", image_bytes=b"\x89PNG\r\n\x1a\n", mime_type="image/png",
    )

    assert parser.get_token_stats()["call_count"] == initial_calls + 1
    assert parser.get_token_stats()["total_prompt_tokens"] >= 500


@pytest.mark.asyncio
async def test_parse_with_image_no_client_returns_none():
    """client 未初始化（沒設 API key）→ 回 None。"""
    config = AiConfig(enabled=True, api_key_env="NONEXISTENT_VAR_XYZ")
    if "NONEXISTENT_VAR_XYZ" in os.environ:
        del os.environ["NONEXISTENT_VAR_XYZ"]
    parser = AiSignalParser(config)
    assert parser.client is None  # confirm setup

    result = await parser.parse_with_image(
        text_content="anything", image_bytes=b"\x89PNG", mime_type="image/png",
    )
    assert result is None
