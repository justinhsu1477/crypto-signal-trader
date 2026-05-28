"""Format Promptfoo result.json → Discord webhook payload.

Sibling to discord-monitor/eval/format_report.py (which reads runner.py output).
Same visual style (emoji + color by score, by-category breakdown, failure list).
Adds: per-provider breakdown when A/B mode is used, token usage + cost summary.

CLI mode:
    python3 format_promptfoo_report.py --input result.json
    cat result.json | python3 format_promptfoo_report.py

Both emit Discord webhook payload JSON to stdout.
"""
from __future__ import annotations

import argparse
import json
import sys
from typing import Any


_MAX_FAILURES_LISTED = 5


def _emoji_for_score(pct: float) -> str:
    if pct >= 95.0:
        return "✅"
    if pct >= 80.0:
        return "⚠️"
    return "🔴"


def _color_for_score(pct: float) -> int:
    if pct >= 95.0:
        return 0x57F287
    if pct >= 80.0:
        return 0xFEE75C
    return 0xED4245


def _provider_label(result_entry: dict) -> str:
    """Human-readable provider name from a result row."""
    provider = result_entry.get("provider") or {}
    return provider.get("label") or provider.get("id") or "unknown"


def _case_id(result_entry: dict) -> str:
    """Stable case id — from testCase.description (set by tests_loader.py)."""
    tc = result_entry.get("testCase") or {}
    return tc.get("description") or "?"


def _failure_reason(result_entry: dict) -> str:
    """Best-effort 1-line failure reason."""
    gr = result_entry.get("gradingResult") or {}
    # Primary: aggregated reason
    reason = gr.get("reason")
    # If aggregated reason is generic ("All assertions passed"), drill into components
    if not reason or reason in ("All assertions passed", "OK"):
        comps = gr.get("componentResults") or []
        for c in comps:
            if not c.get("pass") and c.get("reason"):
                reason = c["reason"]
                break
    return (reason or "?").strip()


def _group_by(rows: list[dict], key_fn) -> dict[str, list[dict]]:
    out: dict[str, list[dict]] = {}
    for r in rows:
        k = key_fn(r)
        out.setdefault(k, []).append(r)
    return out


def format_discord_payload(promptfoo_json: dict[str, Any]) -> dict[str, Any]:
    """Convert Promptfoo result.json → Discord webhook payload."""
    results_root = promptfoo_json.get("results") or {}
    rows: list[dict] = results_root.get("results") or []
    stats: dict = results_root.get("stats") or {}

    if not rows:
        return {
            "content": "🔴 **Eval Report** — no results found",
            "embeds": [],
        }

    # Overall stats
    total = len(rows)
    score_sum = sum(float(r.get("score") or 0) for r in rows)
    passed_count = stats.get("successes", sum(1 for r in rows if r.get("success")))
    fail_count = stats.get("failures", 0) + stats.get("errors", 0)
    overall_pct = (score_sum / total * 100) if total else 0.0

    # Token / cost aggregate — promptfoo's stats.tokenUsage 對自定義 provider 不準，
    # 改從每筆 row.response.tokenUsage 加總（我們 provider 確實有回 tokenUsage）
    total_tokens = sum(
        ((r.get("response") or {}).get("tokenUsage") or {}).get("total", 0) or 0
        for r in rows
    )
    total_cost = sum(float(r.get("cost") or 0) for r in rows)

    # Provider list (label) — single or A/B
    providers = sorted({_provider_label(r) for r in rows})
    is_ab = len(providers) > 1

    # Group failures (score < 0.99) for "Failures" section
    failed = [r for r in rows if float(r.get("score") or 0) < 0.99]

    failure_lines: list[str] = []
    for f in failed[:_MAX_FAILURES_LISTED]:
        case_id = _case_id(f)
        provider = _provider_label(f) if is_ab else ""
        reason = _failure_reason(f)
        if len(reason) > 100:
            reason = reason[:97] + "..."
        prefix = f"[{provider}] " if provider else ""
        failure_lines.append(f"- `{case_id}` {prefix}— {reason}")
    if len(failed) > _MAX_FAILURES_LISTED:
        failure_lines.append(f"_+{len(failed) - _MAX_FAILURES_LISTED} more failures_")

    # By-category breakdown (combine across providers if A/B)
    by_cat = _group_by(rows, lambda r: ((r.get("vars") or {}).get("category") or "unknown"))
    cat_lines: list[str] = []
    for cat, items in sorted(by_cat.items()):
        avg = sum(float(it.get("score") or 0) for it in items) / len(items) * 100
        cat_lines.append(f"- `{cat}`: {avg:.1f}% ({len(items)} cases)")

    # A/B provider comparison block (only if multi-provider)
    # tokenUsage 在 result_row.response.tokenUsage（不是 top-level）— provider
    # 回傳 tokenUsage 後 promptfoo 把它嵌在 response。
    provider_lines: list[str] = []
    if is_ab:
        by_provider = _group_by(rows, _provider_label)
        for p, items in sorted(by_provider.items()):
            p_score = sum(float(it.get("score") or 0) for it in items) / len(items) * 100
            p_tokens = sum(
                ((it.get("response") or {}).get("tokenUsage") or {}).get("total", 0) or 0
                for it in items
            )
            p_cost = sum(float(it.get("cost") or 0) for it in items)
            provider_lines.append(
                f"- `{p}`: {p_score:.1f}% — {p_tokens:,} tok / ${p_cost:.4f}"
            )

    description_parts: list[str] = []
    if provider_lines:
        description_parts.append("**Providers (A/B):**\n" + "\n".join(provider_lines))
    if failure_lines:
        description_parts.append("**Failures:**\n" + "\n".join(failure_lines))
    if cat_lines:
        description_parts.append("**By category:**\n" + "\n".join(cat_lines))

    description = "\n\n".join(description_parts) if description_parts else "All cases passed."

    emoji = _emoji_for_score(overall_pct)
    color = _color_for_score(overall_pct)
    model_str = " vs ".join(providers) if is_ab else providers[0]

    # Header line: score + pass/fail count + tokens / cost
    header_lines = [
        f"Score: **{score_sum:.1f} / {total}** ({overall_pct:.1f}%)  "
        f"— {passed_count} pass / {fail_count} fail",
    ]
    if total_tokens or total_cost:
        header_lines.append(f"Tokens: **{total_tokens:,}**  •  Cost: **${total_cost:.4f}**")

    return {
        "content": f"{emoji} **Eval Report** — `{model_str}`\n" + "\n".join(header_lines),
        "embeds": [
            {
                "title": f"Eval result — {overall_pct:.1f}%",
                "description": description,
                "color": color,
                "footer": {"text": f"Provider: {model_str}"},
            }
        ],
    }


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--input", help="Promptfoo result.json (defaults to stdin)")
    args = ap.parse_args()

    if args.input:
        with open(args.input, "r", encoding="utf-8") as f:
            data = json.load(f)
    else:
        data = json.load(sys.stdin)

    payload = format_discord_payload(data)
    print(json.dumps(payload, ensure_ascii=False))


if __name__ == "__main__":
    main()
