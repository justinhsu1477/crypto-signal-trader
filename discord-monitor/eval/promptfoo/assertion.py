"""Promptfoo Python assertion — reuses existing scorer.py logic.

Promptfoo calls get_assert(output, context) for each test case.
We deserialise the provider's JSON-stringified output and run it through
the legacy scorer for an identical scoring rubric.

This is the bridge that lets us reuse 100% of the scoring rules
(compound list / tolerance / hard-fail rules) without rewriting them in JS.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
EVAL_PKG = HERE.parent
if str(EVAL_PKG.parent) not in sys.path:
    sys.path.insert(0, str(EVAL_PKG.parent))

from eval.scorer import score_case  # noqa: E402


def get_assert(output: str, context: dict) -> dict:
    """Promptfoo assertion entry point.

    Args:
        output: provider's output string (JSON-encoded parsed result)
        context: Promptfoo context containing `vars` (the test case data)

    Returns:
        GradingResult dict: {pass, score, reason}
        Pass threshold matches legacy harness: score >= 1.0 = strict pass,
        but we report exact score so Promptfoo shows partial credit.
    """
    # context["vars"] carries the test case fields we put in promptfoo.yaml tests
    vars_ = context.get("vars", {})

    # Rebuild a "case dict" in the shape scorer expects
    case = {}
    if "expected_list" in vars_:
        case["expected_list"] = vars_["expected_list"]
    if "expected" in vars_:
        case["expected"] = vars_["expected"]
    if "tolerance_pct" in vars_:
        case["tolerance_pct"] = vars_["tolerance_pct"]

    # Deserialise provider output back to dict / list / None
    try:
        actual = json.loads(output) if output else None
    except json.JSONDecodeError as e:
        return {
            "pass": False,
            "score": 0.0,
            "reason": f"provider returned non-JSON output: {e} | raw: {output[:100]}",
        }

    score, failures = score_case(case, actual)

    return {
        "pass": score >= 1.0,
        "score": score,
        "reason": "; ".join(failures) if failures else "OK",
    }
