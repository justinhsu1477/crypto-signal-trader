"""Format runner.py JSON output → Discord webhook payload.

純函式，無 IO，方便單元測試。CLI mode 從 stdin 讀 JSON、stdout 印 payload JSON
（給 GitHub Actions workflow 用 `jq` 或 curl 直接送）。
"""
from __future__ import annotations

import json
import sys
from typing import Any


_MAX_FAILURES_LISTED = 5


def _emoji_for_score(pct: float) -> str:
    """根據分數決定 emoji。"""
    if pct >= 95.0:
        return "✅"
    if pct >= 80.0:
        return "⚠️"
    return "🔴"


def _color_for_score(pct: float) -> int:
    """Discord embed color (decimal)。"""
    if pct >= 95.0:
        return 0x57F287   # green
    if pct >= 80.0:
        return 0xFEE75C   # yellow
    return 0xED4245       # red


def format_discord_payload(runner_result: dict[str, Any], model: str) -> dict[str, Any]:
    """把 runner.run_eval() 輸出轉成 Discord webhook 可吃的 payload dict。

    Discord webhook spec: https://discord.com/developers/docs/resources/webhook#execute-webhook
    """
    overall = float(runner_result.get("overall_pct", 0.0))
    total = int(runner_result.get("total_cases", 0))
    score = float(runner_result.get("total_score", 0.0))
    by_cat = runner_result.get("by_category", {}) or {}
    results = runner_result.get("results", []) or []

    emoji = _emoji_for_score(overall)
    color = _color_for_score(overall)

    # 撈失敗 case（score < 0.99）
    failed_cases = [r for r in results if r.get("score", 0.0) < 0.99]

    # 組失敗摘要文字
    failure_lines: list[str] = []
    for f in failed_cases[:_MAX_FAILURES_LISTED]:
        case_id = f.get("id", "?")
        reasons = f.get("failures") or [f.get("error", "?")]
        first_reason = reasons[0] if reasons else "?"
        # Truncate 長 reason
        if len(first_reason) > 100:
            first_reason = first_reason[:97] + "..."
        failure_lines.append(f"- `{case_id}` — {first_reason}")

    if len(failed_cases) > _MAX_FAILURES_LISTED:
        failure_lines.append(f"_+{len(failed_cases) - _MAX_FAILURES_LISTED} more failures_")

    # By-category 分類摘要
    cat_lines = []
    for cat, avg in sorted(by_cat.items()):
        cat_pct = float(avg) * 100.0
        cat_lines.append(f"- `{cat}`: {cat_pct:.1f}%")

    description_parts: list[str] = []
    if failure_lines:
        description_parts.append("**Failures:**\n" + "\n".join(failure_lines))
    if cat_lines:
        description_parts.append("**By category:**\n" + "\n".join(cat_lines))

    description = "\n\n".join(description_parts) if description_parts else "All cases passed."

    return {
        "content": f"{emoji} **Weekly Eval Report** — `{model}`\n"
                   f"Score: **{score:.1f} / {total}** ({overall:.1f}%)",
        "embeds": [
            {
                "title": f"Eval result — {overall:.1f}%",
                "description": description,
                "color": color,
                "footer": {"text": f"Model: {model}"},
            }
        ],
    }


def main() -> None:
    """CLI: 從 stdin 讀 runner JSON，print Discord payload JSON 到 stdout。

    Usage:
        cat result.json | python3 -m eval.format_report --model gemini-2.5-flash
    """
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True, help="Gemini model name (logged in report)")
    ap.add_argument("--input", help="Input JSON file (defaults to stdin)")
    args = ap.parse_args()

    if args.input:
        with open(args.input, "r", encoding="utf-8") as f:
            data = json.load(f)
    else:
        data = json.load(sys.stdin)

    payload = format_discord_payload(data, model=args.model)
    print(json.dumps(payload, ensure_ascii=False))


if __name__ == "__main__":
    main()
