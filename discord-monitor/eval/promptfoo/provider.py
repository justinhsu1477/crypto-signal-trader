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


# Cache parser instance across calls (Promptfoo invokes call_api once per test)
_parser: AiSignalParser | None = None


def _get_parser() -> AiSignalParser:
    global _parser
    if _parser is None:
        if not os.environ.get("GEMINI_API_KEY"):
            raise RuntimeError("GEMINI_API_KEY env var not set")
        model = os.environ.get("EVAL_GEMINI_MODEL", "gemini-2.5-flash-lite")
        # AiConfig reads api_key by env var NAME (api_key_env), not the literal key
        cfg = AiConfig(
            enabled=True,
            model=model,
            api_key_env="GEMINI_API_KEY",
            max_retries=2,
            retry_delays=[5, 10],
        )
        _parser = AiSignalParser(cfg)
    return _parser


def call_api(prompt: str, options: dict, context: dict) -> dict:
    """Promptfoo provider entry point.

    Args:
        prompt: the discord message text (Promptfoo treats per-case `input` as the prompt)
        options: provider config from promptfoo.yaml (unused for now)
        context: test context (contains `vars` from the test case)

    Returns:
        {"output": <JSON string of parsed result>} — Promptfoo serialises dict/list
        as JSON string for assertion comparison.
    """
    parser = _get_parser()

    # Run async parse() in a fresh event loop (Promptfoo invokes us sync-style)
    result = asyncio.run(parser.parse(prompt))

    # Promptfoo expects `output` to be string-friendly. Serialize compound list
    # or single dict consistently for assertion to JSON.parse later.
    if result is None:
        output_str = "null"
    else:
        output_str = json.dumps(result, ensure_ascii=False)

    return {
        "output": output_str,
    }
