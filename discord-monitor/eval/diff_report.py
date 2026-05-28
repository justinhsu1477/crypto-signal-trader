"""Diff two eval runner JSON results → markdown report + regression gate.

CLI:
    python -m eval.diff_report base.json head.json [--output diff.md]

Exit code:
    0 — no regression (head >= base, or only improvements / new cases)
    1 — regression detected (any case PASS→FAIL in common set, OR overall drop > THRESHOLD_PCT)

Designed for GitHub Actions PR-time gate: produces a PR-friendly markdown
comment AND exit code in one pass.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


# 整體分數允許的下降幅度（百分點）。低於此 fail PR。
# 例：base 100% / head 98% → drop 2 → OK；head 97% → drop 3 → fail
OVERALL_DROP_THRESHOLD_PCT = 2.0

# 判 PASS 的最低分數。<0.99 視為 FAIL（與 runner.py 同設定）。
PASS_THRESHOLD = 0.99


def _classify(score: float) -> str:
    if score >= PASS_THRESHOLD:
        return "PASS"
    if score >= 0.5:
        return "WARN"
    return "FAIL"


def compute_diff(base: dict, head: dict) -> dict[str, Any]:
    """Compute per-case + overall diff. Pure function — no IO.

    Returns dict with keys: overall_delta, regressed, improved, new_cases,
    removed_cases, unchanged_count, has_regression, category_delta.
    """
    base_results = {r["id"]: r for r in base.get("results", [])}
    head_results = {r["id"]: r for r in head.get("results", [])}

    base_ids = set(base_results.keys())
    head_ids = set(head_results.keys())
    common_ids = base_ids & head_ids
    new_ids = head_ids - base_ids
    removed_ids = base_ids - head_ids

    regressed: list[dict] = []
    improved: list[dict] = []
    unchanged_count = 0

    for cid in sorted(common_ids):
        bs = base_results[cid]["score"]
        hs = head_results[cid]["score"]
        b_class = _classify(bs)
        h_class = _classify(hs)
        if b_class == "PASS" and h_class != "PASS":
            regressed.append({
                "id": cid,
                "category": head_results[cid].get("category", ""),
                "base_score": bs,
                "head_score": hs,
                "head_failures": head_results[cid].get("failures") or [head_results[cid].get("error", "?")],
            })
        elif b_class != "PASS" and h_class == "PASS":
            improved.append({
                "id": cid,
                "category": head_results[cid].get("category", ""),
                "base_score": bs,
                "head_score": hs,
            })
        else:
            unchanged_count += 1

    new_cases = [
        {
            "id": cid,
            "category": head_results[cid].get("category", ""),
            "score": head_results[cid]["score"],
            "passed": _classify(head_results[cid]["score"]) == "PASS",
        }
        for cid in sorted(new_ids)
    ]

    removed_cases = [
        {
            "id": cid,
            "category": base_results[cid].get("category", ""),
            "base_score": base_results[cid]["score"],
        }
        for cid in sorted(removed_ids)
    ]

    overall_delta = float(head.get("overall_pct", 0.0)) - float(base.get("overall_pct", 0.0))

    # Per-category delta
    base_cats = base.get("by_category", {}) or {}
    head_cats = head.get("by_category", {}) or {}
    all_cats = sorted(set(base_cats.keys()) | set(head_cats.keys()))
    category_delta = []
    for cat in all_cats:
        b = float(base_cats.get(cat, 0.0)) * 100
        h = float(head_cats.get(cat, 0.0)) * 100
        category_delta.append({
            "category": cat,
            "base_pct": b,
            "head_pct": h,
            "delta": h - b,
        })

    has_regression = bool(regressed) or overall_delta < -OVERALL_DROP_THRESHOLD_PCT

    return {
        "overall_delta": overall_delta,
        "base_overall": float(base.get("overall_pct", 0.0)),
        "head_overall": float(head.get("overall_pct", 0.0)),
        "regressed": regressed,
        "improved": improved,
        "new_cases": new_cases,
        "removed_cases": removed_cases,
        "unchanged_count": unchanged_count,
        "category_delta": category_delta,
        "has_regression": has_regression,
    }


def format_markdown(diff: dict, model: str = "gemini-2.5-flash") -> str:
    """Render diff dict → PR-friendly markdown comment."""
    lines: list[str] = []

    # Header with verdict
    if diff["has_regression"]:
        lines.append("## 🔴 Eval Regression Detected")
    elif diff["overall_delta"] > 0 or diff["improved"]:
        lines.append("## ✅ Eval Improved")
    else:
        lines.append("## ✅ Eval Stable")

    lines.append("")
    lines.append(
        f"**Overall**: {diff['base_overall']:.1f}% → **{diff['head_overall']:.1f}%** "
        f"({diff['overall_delta']:+.1f} pp) · `{model}`"
    )
    lines.append("")

    # Regression block — most important
    if diff["regressed"]:
        lines.append(f"### 🔴 Regressed ({len(diff['regressed'])} case{'s' if len(diff['regressed']) > 1 else ''})")
        lines.append("")
        lines.append("| Case | Category | Base | Head | First failure |")
        lines.append("|---|---|---:|---:|---|")
        for r in diff["regressed"]:
            first_fail = r["head_failures"][0] if r["head_failures"] else "?"
            if len(first_fail) > 80:
                first_fail = first_fail[:77] + "..."
            # escape pipe in markdown table
            first_fail = first_fail.replace("|", "\\|")
            lines.append(
                f"| `{r['id']}` | `{r['category']}` | {r['base_score']:.2f} | {r['head_score']:.2f} | {first_fail} |"
            )
        lines.append("")

    # Improvements
    if diff["improved"]:
        lines.append(f"### ✨ Improved ({len(diff['improved'])} case{'s' if len(diff['improved']) > 1 else ''})")
        lines.append("")
        for i in diff["improved"]:
            lines.append(f"- `{i['id']}` (`{i['category']}`): {i['base_score']:.2f} → {i['head_score']:.2f}")
        lines.append("")

    # New cases
    if diff["new_cases"]:
        passed = [c for c in diff["new_cases"] if c["passed"]]
        failed = [c for c in diff["new_cases"] if not c["passed"]]
        lines.append(f"### 🆕 New cases ({len(diff['new_cases'])})")
        lines.append("")
        if failed:
            lines.append(f"**❌ Not passing ({len(failed)}):**")
            for c in failed:
                lines.append(f"- `{c['id']}` (`{c['category']}`): {c['score']:.2f}")
            lines.append("")
        if passed:
            lines.append(f"**✅ Passing ({len(passed)}):** " + ", ".join(f"`{c['id']}`" for c in passed))
            lines.append("")

    # Removed cases
    if diff["removed_cases"]:
        lines.append(f"### 🗑️ Removed cases ({len(diff['removed_cases'])})")
        lines.append("")
        for r in diff["removed_cases"]:
            lines.append(f"- `{r['id']}` (`{r['category']}`)")
        lines.append("")

    # Category delta (only show if non-trivial)
    cat_changes = [c for c in diff["category_delta"] if abs(c["delta"]) > 0.5]
    if cat_changes:
        lines.append("### 📊 By category")
        lines.append("")
        lines.append("| Category | Base | Head | Δ |")
        lines.append("|---|---:|---:|---:|")
        for c in cat_changes:
            arrow = "↑" if c["delta"] > 0 else "↓"
            lines.append(
                f"| `{c['category']}` | {c['base_pct']:.1f}% | {c['head_pct']:.1f}% | {arrow} {abs(c['delta']):.1f} pp |"
            )
        lines.append("")

    # Footer
    lines.append("---")
    lines.append(
        f"_Unchanged: {diff['unchanged_count']} cases · "
        f"Regression gate: overall drop > {OVERALL_DROP_THRESHOLD_PCT} pp OR any PASS→FAIL_"
    )

    return "\n".join(lines)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("base", help="Baseline JSON path (from main branch)")
    ap.add_argument("head", help="Head JSON path (from PR)")
    ap.add_argument("--output", help="Write markdown to this file (default: stdout)")
    ap.add_argument("--model", default="gemini-2.5-flash", help="Model name shown in report")
    args = ap.parse_args()

    base = json.loads(Path(args.base).read_text(encoding="utf-8"))
    head = json.loads(Path(args.head).read_text(encoding="utf-8"))

    diff = compute_diff(base, head)
    md = format_markdown(diff, model=args.model)

    if args.output:
        Path(args.output).write_text(md, encoding="utf-8")
        print(f"Diff report written to {args.output}", file=sys.stderr)
    else:
        print(md)

    # Exit code: 1 if regression, 0 otherwise
    sys.exit(1 if diff["has_regression"] else 0)


if __name__ == "__main__":
    main()
