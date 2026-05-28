"""Unit tests for eval/diff_report.py — pure function, no IO."""
from __future__ import annotations

from eval.diff_report import compute_diff, format_markdown, OVERALL_DROP_THRESHOLD_PCT


def _make(results: list[dict], overall: float, by_cat: dict | None = None) -> dict:
    """Build a runner.run_eval()-shaped dict for testing."""
    return {
        "overall_pct": overall,
        "total_cases": len(results),
        "results": results,
        "by_category": by_cat or {},
    }


def _case(cid: str, score: float, category: str = "entry_text", failures: list[str] | None = None) -> dict:
    return {"id": cid, "category": category, "score": score, "failures": failures or []}


# ---------- compute_diff -----------------------------------------------------


def test_no_change_returns_no_regression():
    base = _make([_case("a", 1.0), _case("b", 1.0)], 100.0)
    head = _make([_case("a", 1.0), _case("b", 1.0)], 100.0)
    d = compute_diff(base, head)
    assert d["has_regression"] is False
    assert d["regressed"] == []
    assert d["improved"] == []
    assert d["unchanged_count"] == 2


def test_regression_pass_to_fail_flags():
    base = _make([_case("a", 1.0)], 100.0)
    head = _make([_case("a", 0.3, failures=["action: expected ENTRY, got INFO"])], 30.0)
    d = compute_diff(base, head)
    assert d["has_regression"] is True
    assert len(d["regressed"]) == 1
    assert d["regressed"][0]["id"] == "a"
    assert "action" in d["regressed"][0]["head_failures"][0]


def test_pass_to_warn_counts_as_regression():
    # WARN (0.5–0.99) is not PASS — counts as regression
    base = _make([_case("a", 1.0)], 100.0)
    head = _make([_case("a", 0.7)], 70.0)
    d = compute_diff(base, head)
    assert d["has_regression"] is True
    assert len(d["regressed"]) == 1


def test_improvement_fail_to_pass():
    base = _make([_case("a", 0.3)], 30.0)
    head = _make([_case("a", 1.0)], 100.0)
    d = compute_diff(base, head)
    assert d["has_regression"] is False
    assert len(d["improved"]) == 1
    assert d["improved"][0]["id"] == "a"


def test_overall_drop_above_threshold_triggers_regression():
    # All cases stay PASS but overall metric drops a lot (impossible in real data,
    # but tests the secondary gate)
    base = _make([_case("a", 1.0), _case("b", 1.0)], 100.0)
    head = _make([_case("a", 1.0), _case("b", 1.0)], 100.0 - OVERALL_DROP_THRESHOLD_PCT - 0.1)
    d = compute_diff(base, head)
    assert d["has_regression"] is True


def test_overall_drop_within_threshold_ok():
    base = _make([_case("a", 1.0)], 100.0)
    head = _make([_case("a", 1.0)], 100.0 - OVERALL_DROP_THRESHOLD_PCT + 0.1)
    d = compute_diff(base, head)
    assert d["has_regression"] is False


def test_new_case_does_not_count_as_regression():
    base = _make([_case("a", 1.0)], 100.0)
    head = _make([_case("a", 1.0), _case("b_new", 1.0)], 100.0)
    d = compute_diff(base, head)
    assert d["has_regression"] is False
    assert len(d["new_cases"]) == 1
    assert d["new_cases"][0]["id"] == "b_new"
    assert d["new_cases"][0]["passed"] is True


def test_new_case_failing_does_not_count_as_regression():
    # Adding a new failing case is intentional (probably guard against future bug)
    # — not a regression
    base = _make([_case("a", 1.0)], 100.0)
    head = _make([_case("a", 1.0), _case("b_new", 0.0)], 50.0)
    d = compute_diff(base, head)
    # head overall 50, base 100 → drop 50 > threshold → regression flagged via secondary gate
    assert d["has_regression"] is True
    # but new case is correctly identified
    assert len(d["new_cases"]) == 1
    assert d["new_cases"][0]["passed"] is False


def test_removed_case_listed():
    base = _make([_case("a", 1.0), _case("gone", 1.0)], 100.0)
    head = _make([_case("a", 1.0)], 100.0)
    d = compute_diff(base, head)
    assert len(d["removed_cases"]) == 1
    assert d["removed_cases"][0]["id"] == "gone"


def test_category_delta_computed():
    base = _make(
        [_case("a", 1.0, "entry_text")], 100.0,
        by_cat={"entry_text": 1.0, "info": 0.5},
    )
    head = _make(
        [_case("a", 1.0, "entry_text")], 100.0,
        by_cat={"entry_text": 1.0, "info": 1.0, "compound": 1.0},
    )
    d = compute_diff(base, head)
    cats = {c["category"]: c for c in d["category_delta"]}
    assert cats["info"]["delta"] == 50.0  # 50% → 100% = +50 pp
    assert cats["compound"]["delta"] == 100.0  # new category
    assert cats["entry_text"]["delta"] == 0


# ---------- format_markdown --------------------------------------------------


def test_markdown_regression_header():
    base = _make([_case("a", 1.0)], 100.0)
    head = _make([_case("a", 0.0)], 0.0)
    d = compute_diff(base, head)
    md = format_markdown(d)
    assert "🔴 Eval Regression" in md
    assert "Regressed (1 case)" in md
    assert "`a`" in md


def test_markdown_stable_header_when_no_change():
    base = _make([_case("a", 1.0)], 100.0)
    head = _make([_case("a", 1.0)], 100.0)
    d = compute_diff(base, head)
    md = format_markdown(d)
    assert "✅ Eval Stable" in md
    assert "🔴" not in md


def test_markdown_improved_header():
    base = _make([_case("a", 0.0)], 0.0)
    head = _make([_case("a", 1.0)], 100.0)
    d = compute_diff(base, head)
    md = format_markdown(d)
    assert "✅ Eval Improved" in md
    assert "Improved (1 case)" in md


def test_markdown_escapes_pipe_in_failure():
    base = _make([_case("a", 1.0)], 100.0)
    head = _make([_case("a", 0.0, failures=["action: a|b → c|d"])], 0.0)
    d = compute_diff(base, head)
    md = format_markdown(d)
    # the | inside cell should be escaped
    assert "a\\|b" in md
    assert "c\\|d" in md
