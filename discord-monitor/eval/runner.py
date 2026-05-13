"""Eval harness runner — calls real AiSignalParser against curated cases.

Usage:
    cd discord-monitor
    python -m eval.runner                    # uses real Gemini API
    python -m eval.runner --filter entry     # only run cases with id prefix
    python -m eval.runner --json results.json # output JSON

Exit code:
    0 if overall score >= 80% (good for CI gate)
    1 otherwise
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
from pathlib import Path

from src.ai_parser import AiSignalParser
from src.config import AiConfig

from .scorer import score_case


HERE = Path(__file__).parent
CASES_FILE = HERE / "cases.jsonl"


def load_cases() -> list[dict]:
    """Load curated cases from cases.jsonl (one JSON per line, '#' = comment)."""
    cases: list[dict] = []
    for line in CASES_FILE.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        cases.append(json.loads(stripped))
    return cases


async def run_eval(filter_prefix: str | None = None, max_cases: int | None = None) -> dict:
    """Run all cases through the real AiSignalParser and aggregate scores."""
    api_key = os.environ.get("GEMINI_API_KEY", "")
    if not api_key:
        print("ERROR: GEMINI_API_KEY not set", file=sys.stderr)
        sys.exit(1)

    config = AiConfig(
        enabled=True,
        model=os.environ.get("EVAL_GEMINI_MODEL", "gemini-2.0-flash"),
        api_key_env="GEMINI_API_KEY",
    )
    parser = AiSignalParser(config)

    cases = load_cases()
    if filter_prefix:
        cases = [c for c in cases if c["id"].startswith(filter_prefix)]
    if max_cases:
        cases = cases[:max_cases]

    print(f"Running {len(cases)} cases against {config.model}...\n")

    results: list[dict] = []
    total_score = 0.0

    for case in cases:
        print(f"  {case['id']:30s} ", end="", flush=True)
        try:
            actual = await parser.parse(case["input"])
            score, failures = score_case(case, actual)
            status = "PASS" if score >= 0.99 else ("WARN" if score >= 0.5 else "FAIL")
            print(f"{status} {score:.2f}")
            if failures:
                for f in failures:
                    print(f"      | {f}")
            results.append({
                "id": case["id"],
                "category": case.get("category", ""),
                "score": score,
                "failures": failures,
                "actual": actual if isinstance(actual, (dict, list)) else str(actual),
            })
            total_score += score
        except Exception as e:
            print(f"FAIL ERROR: {e}")
            results.append({
                "id": case["id"],
                "category": case.get("category", ""),
                "score": 0.0,
                "error": str(e),
            })

    overall = total_score / len(cases) if cases else 0

    # Per-category breakdown
    by_cat: dict[str, list[float]] = {}
    for r in results:
        cat = r.get("category", "uncategorized")
        by_cat.setdefault(cat, []).append(r["score"])

    print(f"\n{'=' * 50}")
    print(f"Overall: {total_score:.1f} / {len(cases)} ({overall * 100:.1f}%)")
    print(f"{'=' * 50}")
    for cat, scores in sorted(by_cat.items()):
        avg = sum(scores) / len(scores)
        print(f"  {cat:20s} {avg * 100:5.1f}%  ({len(scores)} cases)")

    return {
        "total_cases": len(cases),
        "total_score": total_score,
        "overall_pct": overall * 100,
        "by_category": {cat: sum(s) / len(s) for cat, s in by_cat.items()},
        "results": results,
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--filter", help="Only run cases with this id prefix")
    ap.add_argument("--max", type=int, help="Only run first N cases")
    ap.add_argument("--json", help="Output results JSON to this file")
    args = ap.parse_args()

    summary = asyncio.run(run_eval(args.filter, args.max))

    if args.json:
        Path(args.json).write_text(
            json.dumps(summary, indent=2, ensure_ascii=False),
        )
        print(f"\nResults saved to {args.json}")

    # Exit code: 0 if overall >= 80%, 1 otherwise (for CI)
    sys.exit(0 if summary["overall_pct"] >= 80 else 1)


if __name__ == "__main__":
    main()
