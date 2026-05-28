"""Promptfoo test loader — converts cases.jsonl into Promptfoo test format.

Promptfoo can call this to generate tests dynamically.
Set EVAL_FILTER env var to limit to id prefix (e.g. EVAL_FILTER=compound).
Set EVAL_LIMIT to cap the number of cases (e.g. EVAL_LIMIT=5 for PoC).
"""
from __future__ import annotations

import json
import os
from pathlib import Path

HERE = Path(__file__).resolve().parent
CASES_FILE = HERE.parent / "cases.jsonl"


def generate_tests() -> list[dict]:
    """Promptfoo entry — return list of test dicts."""
    filter_prefix = os.environ.get("EVAL_FILTER", "")
    limit = os.environ.get("EVAL_LIMIT")
    limit_n = int(limit) if limit and limit.isdigit() else None

    tests: list[dict] = []
    for line in CASES_FILE.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        case = json.loads(stripped)

        # Optional filtering
        if filter_prefix and not case.get("id", "").startswith(filter_prefix):
            continue

        # Build Promptfoo test entry
        # - description: case id for human-readable report
        # - vars: passed to assertion (case dict reconstruction)
        # - vars.input: the prompt content (provider reads this)
        var_dict: dict = {
            "input": case["input"],
            "category": case.get("category", "unknown"),
        }
        if "expected" in case:
            var_dict["expected"] = case["expected"]
        if "expected_list" in case:
            var_dict["expected_list"] = case["expected_list"]
        if "tolerance_pct" in case:
            var_dict["tolerance_pct"] = case["tolerance_pct"]

        tests.append({
            "description": case["id"],
            "vars": var_dict,
        })

        if limit_n is not None and len(tests) >= limit_n:
            break

    return tests
