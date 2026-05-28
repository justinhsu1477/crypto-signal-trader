"""format_promptfoo_report.py 單元測試 — 純函式，不打 Promptfoo CLI。

跟 test_format_report.py 平行，schema 是 Promptfoo result.json 而非 runner.py output。
"""
from __future__ import annotations

import sys
from pathlib import Path

# 動態 import — promptfoo/ 不是標準 Python package
PROMPTFOO_DIR = Path(__file__).resolve().parent.parent / "promptfoo"
if str(PROMPTFOO_DIR) not in sys.path:
    sys.path.insert(0, str(PROMPTFOO_DIR))

from format_promptfoo_report import format_discord_payload  # noqa: E402


# ============================================================
# Helpers — 組 promptfoo result.json shape
# ============================================================

def _row(
    case_id: str,
    category: str,
    score: float,
    provider_label: str = "gemini-flash",
    pass_: bool | None = None,
    reason: str = "OK",
    tokens: int = 100,
    cost: float = 0.0001,
) -> dict:
    """組一個 promptfoo result row。"""
    return {
        "id": f"row-{case_id}",
        "score": score,
        "success": pass_ if pass_ is not None else (score >= 0.99),
        "vars": {"input": "...", "category": category, "expected": {}},
        "testCase": {"description": case_id},
        "provider": {"id": "file://provider.py", "label": provider_label},
        "gradingResult": {
            "pass": pass_ if pass_ is not None else (score >= 0.99),
            "score": score,
            "reason": reason,
            "componentResults": [
                {"pass": score >= 0.99, "score": score, "reason": reason,
                 "assertion": {"type": "python"}}
            ],
        },
        "response": {
            "output": "{}",
            "tokenUsage": {"prompt": tokens // 2, "completion": tokens // 2, "total": tokens},
        },
        "cost": cost,
    }


def _promptfoo_json(rows: list[dict]) -> dict:
    """組完整 promptfoo result.json shape。"""
    passes = sum(1 for r in rows if r["success"])
    fails = len(rows) - passes
    return {
        "evalId": "test-eval",
        "results": {
            "results": rows,
            "stats": {
                "successes": passes,
                "failures": fails,
                "errors": 0,
                "tokenUsage": {"total": 0},  # 故意 0 — 測 formatter 從 row.response 取
            },
        },
    }


# ============================================================
# 邊界 / score → emoji 行為
# ============================================================

def test_perfect_score_uses_green_emoji():
    data = _promptfoo_json([
        _row("c1", "entry_text", 1.0),
        _row("c2", "entry_text", 1.0),
    ])
    payload = format_discord_payload(data)
    assert "✅" in payload["content"]
    assert "100.0%" in payload["content"]


def test_80_to_95_uses_warning_emoji():
    rows = [_row(f"pass_{i}", "entry_text", 1.0) for i in range(8)]
    rows += [_row(f"fail_{i}", "entry_text", 0.0) for i in range(2)]
    payload = format_discord_payload(_promptfoo_json(rows))
    assert "⚠️" in payload["content"]


def test_below_80_uses_alert_emoji():
    rows = [_row(f"pass_{i}", "entry_text", 1.0) for i in range(7)]
    rows += [_row(f"fail_{i}", "entry_text", 0.0) for i in range(3)]
    payload = format_discord_payload(_promptfoo_json(rows))
    assert "🔴" in payload["content"]


def test_empty_results_returns_alert_payload():
    payload = format_discord_payload({"results": {"results": [], "stats": {}}})
    assert "🔴" in payload["content"]


# ============================================================
# 失敗列表
# ============================================================

def test_failures_listed_with_case_id():
    rows = [
        _row("entry_text_001", "entry_text", 1.0),
        _row("compound_005", "compound", 0.5,
             reason="symbol: expected None, got BTCUSDT"),
    ]
    payload = format_discord_payload(_promptfoo_json(rows))
    desc = payload["embeds"][0]["description"]
    assert "compound_005" in desc
    assert "symbol: expected None" in desc


def test_failures_uses_component_reason_when_aggregated_is_generic():
    """gradingResult.reason='All assertions passed' 算 generic，要降到 componentResults。"""
    row = _row("c1", "entry_text", 0.5, reason="All assertions passed")
    # 覆寫 componentResults 用比較具體的 reason
    row["gradingResult"]["componentResults"][0]["pass"] = False
    row["gradingResult"]["componentResults"][0]["reason"] = "side: expected LONG, got SHORT"
    payload = format_discord_payload(_promptfoo_json([row]))
    assert "side: expected LONG" in payload["embeds"][0]["description"]


def test_failures_truncated_at_max_5():
    rows = [_row(f"f_{i:03d}", "x", 0.0) for i in range(8)]
    payload = format_discord_payload(_promptfoo_json(rows))
    desc = payload["embeds"][0]["description"]
    # 應顯示 +3 more（8 - 5 = 3）
    assert "+3 more" in desc


# ============================================================
# By-category
# ============================================================

def test_by_category_groups_correctly():
    rows = [
        _row("c1", "entry_text", 1.0),
        _row("c2", "entry_text", 1.0),
        _row("c3", "compound", 0.5),
    ]
    payload = format_discord_payload(_promptfoo_json(rows))
    desc = payload["embeds"][0]["description"]
    assert "`entry_text`: 100.0%" in desc
    assert "`compound`: 50.0%" in desc


# ============================================================
# A/B mode（multi-provider）
# ============================================================

def test_ab_mode_shows_per_provider_section():
    rows = [
        _row("c1", "entry_text", 1.0, provider_label="flash-2.5", cost=0.001, tokens=1000),
        _row("c2", "entry_text", 1.0, provider_label="flash-2.5", cost=0.001, tokens=1000),
        _row("c1", "entry_text", 1.0, provider_label="pro-2.5",   cost=0.010, tokens=1000),
        _row("c2", "entry_text", 1.0, provider_label="pro-2.5",   cost=0.010, tokens=1000),
    ]
    payload = format_discord_payload(_promptfoo_json(rows))
    desc = payload["embeds"][0]["description"]
    assert "Providers (A/B)" in desc
    assert "flash-2.5" in desc
    assert "pro-2.5" in desc
    # cost 顯示出來
    assert "$0.0020" in desc   # flash total
    assert "$0.0200" in desc   # pro total
    # header 顯示「vs」
    assert "vs" in payload["content"]


def test_single_provider_skips_ab_block():
    rows = [_row(f"c{i}", "x", 1.0) for i in range(3)]
    payload = format_discord_payload(_promptfoo_json(rows))
    assert "Providers (A/B)" not in payload["embeds"][0]["description"]


# ============================================================
# Token + cost header
# ============================================================

def test_tokens_aggregate_from_response_not_stats():
    """stats.tokenUsage 對自定義 provider 不可靠 — 應從 row.response.tokenUsage 加總。"""
    rows = [
        _row("c1", "x", 1.0, tokens=10000, cost=0.001),
        _row("c2", "x", 1.0, tokens=20000, cost=0.002),
    ]
    payload = format_discord_payload(_promptfoo_json(rows))
    # 30,000 tokens total（stats 那邊故意是 0）
    assert "30,000" in payload["content"]
    assert "$0.0030" in payload["content"]
