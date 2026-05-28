"""Promptfoo Python provider — wraps the real AiSignalParser.

Promptfoo calls call_api() for each test case, passing the discord message as `prompt`.
We invoke AiSignalParser.parse() (which goes to Gemini with the actual production
system prompt) and return its parsed dict / list / None.

Why wrap instead of using Promptfoo's native Gemini provider?
- Existing parser does post-processing (compound detection, validation, fallback).
- We want to test the *full pipeline* — same code path as production runs.

Env requirements:
- GEMINI_API_KEY      (required)
- EVAL_GEMINI_MODEL   (optional, default = gemini-2.5-flash-lite)
"""
from __future__ import annotations

import asyncio
import json
import os
import sys
from pathlib import Path

# Make sibling `src/` and parent eval package importable when promptfoo runs us
HERE = Path(__file__).resolve().parent           # .../discord-monitor/eval/promptfoo
DISCORD_MONITOR = HERE.parent.parent              # .../discord-monitor
if str(DISCORD_MONITOR) not in sys.path:
    sys.path.insert(0, str(DISCORD_MONITOR))

from src.ai_parser import AiSignalParser  # noqa: E402
from src.config import AiConfig            # noqa: E402


# Cache parser per model — A/B mode passes different models per call
_parsers: dict[str, AiSignalParser] = {}


def _get_parser(model: str) -> AiSignalParser:
    if model not in _parsers:
        if not os.environ.get("GEMINI_API_KEY"):
            raise RuntimeError("GEMINI_API_KEY env var not set")
        # AiConfig reads api_key by env var NAME (api_key_env), not the literal key
        cfg = AiConfig(
            enabled=True,
            model=model,
            api_key_env="GEMINI_API_KEY",
            max_retries=2,
            retry_delays=[5, 10],
        )
        _parsers[model] = AiSignalParser(cfg)
    return _parsers[model]


def _resolve_model(options: dict) -> str:
    """Model resolution order: per-provider config → env var → default."""
    cfg = (options or {}).get("config") or {}
    model = cfg.get("model")
    if model:
        return model
    return os.environ.get("EVAL_GEMINI_MODEL", "gemini-2.5-flash-lite")


# Gemini 2.5 series pricing (USD per 1M tokens, 2026-05 — 改新版要 update)
_PRICING = {
    "gemini-2.5-flash-lite": {"input": 0.10, "output": 0.40},
    "gemini-2.5-flash":      {"input": 0.30, "output": 2.50},
    "gemini-2.5-pro":        {"input": 1.25, "output": 10.00},
}


def _calculate_cost(model: str, prompt_tokens: int, completion_tokens: int) -> float:
    """USD cost for this call. 0 if model unknown."""
    p = _PRICING.get(model)
    if not p:
        return 0.0
    return (prompt_tokens * p["input"] + completion_tokens * p["output"]) / 1_000_000


def call_api(prompt: str, options: dict, context: dict) -> dict:
    """Promptfoo provider entry point.

    Args:
        prompt: the discord message text (Promptfoo treats per-case `input` as the prompt)
        options: provider config from promptfoo.yaml (unused for now)
        context: test context (contains `vars` from the test case)

    Returns:
        {
          "output":     <JSON string of parsed result>,
          "tokenUsage": {prompt, completion, total} for this call,
          "cost":       USD cost for this call (so Promptfoo aggregates total cost),
        }
    """
    model = _resolve_model(options)
    parser = _get_parser(model)

    # Snapshot tokens before to compute per-call delta (parser tracks cumulative)
    prev_prompt_tokens = parser._total_prompt_tokens
    prev_response_tokens = parser._total_response_tokens

    # Run async parse() in a fresh event loop (Promptfoo invokes us sync-style)
    result = asyncio.run(parser.parse(prompt))

    # Delta tokens for this call
    prompt_tokens = parser._total_prompt_tokens - prev_prompt_tokens
    completion_tokens = parser._total_response_tokens - prev_response_tokens
    total_tokens = prompt_tokens + completion_tokens

    # Promptfoo expects `output` to be string-friendly. Serialize compound list
    # or single dict consistently for assertion to JSON.parse later.
    if result is None:
        output_str = "null"
    else:
        output_str = json.dumps(result, ensure_ascii=False)

    return {
        "output": output_str,
        "tokenUsage": {
            "prompt": prompt_tokens,
            "completion": completion_tokens,
            "total": total_tokens,
        },
        "cost": _calculate_cost(model, prompt_tokens, completion_tokens),
    }
