"""format_report.py 單元測試 — 純函式，不依賴 Gemini / GitHub。

行為合約：
- 接受 runner JSON dict（含 total_cases / overall_pct / results / by_category）
- 回傳符合 Discord webhook spec 的 dict（含 content + embeds）
- failures 列在 embed description（最多 5 個，超過顯示 "+N more"）
- 過 95% → 綠色 emoji；80-95% 黃；< 80% 紅
"""
from __future__ import annotations

import json
from pathlib import Path

from eval.format_report import format_discord_payload


def _runner_result(overall_pct: float, failures: list[dict] | None = None,
                   total_cases: int = 30) -> dict:
    """Helper — 組一個 runner.run_eval() 輸出形狀的 dict."""
    fail_results = failures or []
    pass_results = [
        {"id": f"pass_{i:03d}", "category": "x", "score": 1.0, "failures": []}
        for i in range(total_cases - len(fail_results))
    ]
    return {
        "total_cases": total_cases,
        "total_score": (overall_pct / 100.0) * total_cases,
        "overall_pct": overall_pct,
        "by_category": {"x": overall_pct / 100.0},
        "results": pass_results + fail_results,
    }


def test_perfect_score_uses_green_emoji():
    payload = format_discord_payload(_runner_result(100.0), model="gemini-2.5-flash")
    assert "✅" in payload["content"]
    assert "100.0%" in payload["content"]


def test_80_to_95_uses_warning_emoji():
    payload = format_discord_payload(_runner_result(85.0), model="gemini-2.5-flash")
    assert "⚠️" in payload["content"]


def test_below_80_uses_alert_emoji():
    payload = format_discord_payload(_runner_result(70.0), model="gemini-2.5-flash")
    assert "🔴" in payload["content"]


def test_failures_listed_in_embed():
    failures = [
        {"id": "entry_text_001", "category": "entry_text",
         "score": 0.5, "failures": ["expected ENTRY, got INFO"]},
        {"id": "compound_001", "category": "compound",
         "score": 0.0, "failures": ["expected list[2], got dict"]},
    ]
    payload = format_discord_payload(_runner_result(93.3, failures), model="gemini-2.5-flash")
    embed = payload["embeds"][0]
    assert "entry_text_001" in embed["description"]
    assert "compound_001" in embed["description"]


def test_failures_truncated_when_more_than_5():
    failures = [
        {"id": f"fail_{i:03d}", "category": "x", "score": 0.0,
         "failures": [f"reason {i}"]}
        for i in range(8)
    ]
    payload = format_discord_payload(_runner_result(73.3, failures, 30),
                                      model="gemini-2.5-flash")
    embed = payload["embeds"][0]
    # 前 5 個列出來
    assert "fail_000" in embed["description"]
    assert "fail_004" in embed["description"]
    # 第 6+ 個被截斷
    assert "fail_005" not in embed["description"]
    # 有 "+N more" hint
    assert "more" in embed["description"].lower() or "+" in embed["description"]


def test_payload_includes_model_name():
    payload = format_discord_payload(_runner_result(100.0), model="gemini-2.5-flash")
    assert "gemini-2.5-flash" in payload["content"] or \
           any("gemini-2.5-flash" in e.get("footer", {}).get("text", "")
               for e in payload["embeds"])
