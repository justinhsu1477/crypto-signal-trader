"""Python ↔ Java 契約測試 — Python 端 outgoing payload 不可偏離 fixture。

載入 tests/fixtures/payloads/ 下的 JSON fixture，
呼叫 ApiClient.send_trade(...) 並 patch _post_with_retry 攔截實際送出的 payload，
比對攔截到的 payload 與 fixture 是否完全一致。

當 Python 改 send_trade payload 結構而忘了更新 fixture（或反之）時，這些測試直接抓到。

Why this matters：
Mock 化的 unit tests 對 schema 撒謊，無法防止欄位 drift。
Fixture 給的是「實際 wire format」snapshot，Java 與 Python 雙方都得遵守。
"""
from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import patch

import pytest

from src.api_client import ApiClient, ExecutionResult
from src.config import ApiConfig

# 走到 repo root/tests/fixtures/payloads
FIXTURES_DIR = (
    Path(__file__).resolve().parent.parent.parent / "tests" / "fixtures" / "payloads"
)


def load_fixture(name: str) -> dict:
    """讀取 fixture JSON。"""
    return json.loads((FIXTURES_DIR / name).read_text(encoding="utf-8"))


def _make_client() -> ApiClient:
    """建立啟用 multi_user 模式的 ApiClient（走 /api/broadcast-trade）。"""
    config = ApiConfig(
        base_url="http://localhost:8080",
        multi_user_enabled=True,
        api_key="test-key",
    )
    return ApiClient(config)


async def _fake_post(self, url, payload):  # noqa: D401 - test helper
    """Replace _post_with_retry — 攔截 payload，不真實打網路。"""
    # 寫入 self._captured 讓測試讀
    self._captured_url = url
    self._captured_payload = payload
    return ExecutionResult(success=True, status_code=200, summary="ok")


async def _invoke_send_trade_from_fixture(fixture: dict) -> dict:
    """根據 fixture 拆出 trade_request / source，模擬 signal_router 的呼叫並回傳攔截到的 payload。"""
    captured = {}

    async def fake_post(self, url, payload):
        captured["url"] = url
        captured["payload"] = payload
        return ExecutionResult(success=True, status_code=200, summary="ok")

    # source / signal_timestamp 不該由 caller 傳給 trade_request — 拆出來
    trade_request = {
        k: v for k, v in fixture.items() if k not in ("source", "signal_timestamp")
    }
    source = fixture.get("source")
    fixed_ts_ms = fixture["signal_timestamp"]

    with patch.object(ApiClient, "_post_with_retry", new=fake_post):
        # send_trade 內部用 time.time() 算 signal_timestamp = int(time.time() * 1000)
        # → 凍結讓比較穩定
        with patch("src.api_client.time.time", return_value=fixed_ts_ms / 1000.0):
            client = _make_client()
            await client.send_trade(
                trade_request=trade_request,
                dry_run=False,
                source=source,
            )

    return captured["payload"]


# ==================== text-entry ====================


@pytest.mark.asyncio
async def test_send_trade_text_entry_matches_fixture():
    """Python 送出 text ENTRY 訊號的 payload 必須與 fixture 完全對齊。"""
    fixture = load_fixture("text-entry.json")
    captured = await _invoke_send_trade_from_fixture(fixture)

    # 關鍵欄位斷言
    assert captured["action"] == "ENTRY"
    assert captured["symbol"] == "BTCUSDT"
    assert captured["side"] == "SHORT"
    assert captured["entry_price"] == 82200
    assert captured["stop_loss"] == 83800
    assert captured["take_profit"] == 80600
    assert captured["prompt_version"] == 7
    assert captured["signal_timestamp"] == fixture["signal_timestamp"]

    # source 子物件
    assert captured["source"]["platform"] == "DISCORD"
    assert captured["source"]["message_id"] == "msg_text_001"
    assert captured["source"]["source_name"] == "test-channel"
    # text 訊號不該有 attachment
    assert "attachment" not in captured["source"]

    # 整體 JSON 比對（最嚴格）
    assert captured == fixture, f"Payload drift!\nExpected: {fixture}\nGot: {captured}"


# ==================== image-entry（關鍵：attachment.sha256 audit chain）====================


@pytest.mark.asyncio
async def test_send_trade_image_entry_payload_matches_fixture():
    """Python 送出 image ENTRY 訊號的 payload 必須與 fixture 完全對齊（含 attachment.sha256）。"""
    fixture = load_fixture("image-entry.json")
    captured = await _invoke_send_trade_from_fixture(fixture)

    # attachment.sha256 — 之前 audit bug 的核心欄位
    assert "attachment" in captured["source"]
    assert captured["source"]["attachment"]["sha256"] == (
        "a3b1c8d5e9f2147ba6c3d8e9f10b21c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9"
    )
    assert len(captured["source"]["attachment"]["sha256"]) == 64
    assert captured["source"]["attachment"]["url"].startswith("https://cdn.discordapp.com/")
    assert captured["source"]["attachment"]["filename"] == "signal.png"
    assert captured["source"]["attachment"]["content_type"] == "image/png"
    assert captured["source"]["attachment"]["size"] == 350000

    # 整體 JSON 比對
    assert captured == fixture, f"Payload drift!\nExpected: {fixture}\nGot: {captured}"


# ==================== compound-close-half ====================


@pytest.mark.asyncio
async def test_send_trade_compound_close_half_matches_fixture():
    """Python 送出 compound CLOSE half 訊號的 payload 必須與 fixture 完全對齊。"""
    fixture = load_fixture("compound-close-half.json")
    captured = await _invoke_send_trade_from_fixture(fixture)

    assert captured["action"] == "CLOSE"
    assert captured["close_ratio"] == 0.5
    assert captured["source"]["message_id"].endswith("__close")
    assert captured == fixture, f"Payload drift!\nExpected: {fixture}\nGot: {captured}"


# ==================== compound-movesl-breakeven ====================


@pytest.mark.asyncio
async def test_send_trade_compound_movesl_breakeven_matches_fixture():
    """Python 送出 compound MOVE_SL breakeven 訊號 payload 不可帶 new_stop_loss（→ Java 端自動 breakeven）。"""
    fixture = load_fixture("compound-movesl-breakeven.json")
    captured = await _invoke_send_trade_from_fixture(fixture)

    assert captured["action"] == "MOVE_SL"
    # breakeven 訊號的核心契約：沒有 new_stop_loss → Java 用入場價自動填
    assert "new_stop_loss" not in captured
    assert captured["source"]["message_id"].endswith("__move_sl")
    assert captured == fixture, f"Payload drift!\nExpected: {fixture}\nGot: {captured}"


# ==================== 共用 sanity：所有 fixture 都能載入 ====================


def test_all_fixtures_are_valid_json():
    """fixtures 目錄下的 4 個 JSON 必須能正常 parse。"""
    for name in (
        "text-entry.json",
        "image-entry.json",
        "compound-close-half.json",
        "compound-movesl-breakeven.json",
    ):
        data = load_fixture(name)
        assert "action" in data
        assert "source" in data
        assert "signal_timestamp" in data
