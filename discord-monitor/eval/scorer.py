"""Scoring logic for eval harness — provider-agnostic 0.0 to 1.0 per case."""
from __future__ import annotations
from typing import Any


def score_case(case: dict, actual: Any) -> tuple[float, list[str]]:
    """Compare actual parser output to expected.

    Returns (score, list_of_failures).

    Scoring:
    - action mismatch: 0.0 (hard fail — wrong direction is catastrophic)
    - symbol mismatch: 0.0 (also catastrophic)
    - side mismatch: -0.3
    - entry_price outside tolerance: -0.2
    - stop_loss outside tolerance: -0.2
    - close_ratio mismatch (compound): -0.2 per item
    """
    failures: list[str] = []

    # Handle compound (list of actions)
    if "expected_list" in case:
        if not isinstance(actual, list):
            return 0.0, [f"expected list, got {type(actual).__name__}: {actual}"]
        return _score_compound(case["expected_list"], actual)

    expected = case["expected"]

    # If expected INFO and got INFO (or None) → perfect
    if expected.get("action") == "INFO":
        if actual is None or (isinstance(actual, dict) and actual.get("action") == "INFO"):
            return 1.0, []
        return 0.0, [f"expected INFO, got {actual}"]

    if actual is None:
        return 0.0, ["got None from parser"]

    if isinstance(actual, list):
        return 0.0, ["expected single dict, got list (compound)"]

    score = 1.0

    if actual.get("action") != expected.get("action"):
        return 0.0, [f"action: expected {expected.get('action')}, got {actual.get('action')}"]

    if actual.get("symbol") != expected.get("symbol"):
        return 0.0, [f"symbol: expected {expected.get('symbol')}, got {actual.get('symbol')}"]

    if expected.get("side") is not None and actual.get("side") != expected.get("side"):
        score -= 0.3
        failures.append(f"side: expected {expected.get('side')}, got {actual.get('side')}")

    tolerance = case.get("tolerance_pct", 1.0)
    for field in ("entry_price", "stop_loss"):
        exp_val = expected.get(field)
        if exp_val is None:
            continue
        act_val = actual.get(field)
        if act_val is None:
            score -= 0.2
            failures.append(f"{field}: expected {exp_val}, got null")
            continue
        diff_pct = abs(act_val - exp_val) / exp_val * 100
        if diff_pct > tolerance:
            score -= 0.2
            failures.append(f"{field}: expected {exp_val}, got {act_val} ({diff_pct:.1f}% off)")

    # close_ratio check for single CLOSE actions
    exp_ratio = expected.get("close_ratio")
    if exp_ratio is not None:
        act_ratio = actual.get("close_ratio")
        if act_ratio is None:
            score -= 0.2
            failures.append(f"close_ratio: expected {exp_ratio}, got null")
        elif abs(act_ratio - exp_ratio) > 0.05:
            score -= 0.2
            failures.append(f"close_ratio: expected {exp_ratio}, got {act_ratio}")

    return max(score, 0.0), failures


def _score_compound(expected_list: list, actual_list: list) -> tuple[float, list[str]]:
    """Score compound action (CLOSE + MOVE_SL)."""
    failures: list[str] = []
    if len(actual_list) != len(expected_list):
        return 0.0, [f"compound length: expected {len(expected_list)}, got {len(actual_list)}"]

    score = 1.0
    for i, exp in enumerate(expected_list):
        act = actual_list[i]
        if act.get("action") != exp.get("action"):
            score -= 0.5
            failures.append(
                f"compound[{i}].action: expected {exp.get('action')}, got {act.get('action')}"
            )
        # check close_ratio if expected
        if exp.get("close_ratio") is not None and act.get("close_ratio") is not None:
            if abs(act["close_ratio"] - exp["close_ratio"]) > 0.05:
                score -= 0.2
                failures.append(
                    f"compound[{i}].close_ratio: expected {exp['close_ratio']}, "
                    f"got {act.get('close_ratio')}"
                )

    return max(score, 0.0), failures
