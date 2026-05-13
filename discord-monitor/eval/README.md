# Eval Harness

Automated quality benchmark for the AI signal parser. Runs real Gemini API calls
against curated `cases.jsonl` and produces a score report.

## Why

Without this, every prompt change is a blind change. With it, you change prompt
then run eval, see score delta, then keep or revert.

## Usage

```bash
cd discord-monitor
export GEMINI_API_KEY=your_key

# Run all cases
python -m eval.runner

# Run only entry text cases
python -m eval.runner --filter entry_text

# Save JSON output
python -m eval.runner --json results.json
```

Exit code: 0 if overall >= 80%, 1 otherwise (good for CI gate).

## Adding cases

Each case is one JSON per line in `cases.jsonl`:

```json
{"id": "entry_text_011", "category": "entry_text", "input": "raw discord message text", "expected": {"action": "ENTRY", "symbol": "BTCUSDT", "side": "SHORT", "entry_price": 82200, "stop_loss": 83800}, "tolerance_pct": 1.0}
```

For compound (CLOSE + MOVE_SL):

```json
{"id": "compound_006", "category": "compound", "input": "...", "expected_list": [{"action": "CLOSE", "close_ratio": 0.5}, {"action": "MOVE_SL"}]}
```

For INFO / non-signal:

```json
{"id": "info_006", "category": "info", "input": "上次止盈50%救了我", "expected": {"action": "INFO"}}
```

## Categories

- `entry_text_*` — straightforward text ENTRY
- `compound_*` — CLOSE + MOVE_SL pattern
- `close_only_*` — just CLOSE
- `move_sl_only_*` — just MOVE_SL
- `info_*` — commentary / past-tense / ads (no trade action)
- `messy_*` — chen-ge mixed content
- `eth_filter_*` — non-BTC signals (parser detects symbol correctly even if downstream filters)

## Scoring

| Mismatch | Penalty |
|---|---|
| action wrong | hard fail (0.0) |
| symbol wrong | hard fail (0.0) |
| side wrong | -0.3 |
| entry_price outside tolerance | -0.2 |
| stop_loss outside tolerance | -0.2 |
| close_ratio outside 0.05 | -0.2 |
| compound length wrong | hard fail (0.0) |
| compound action wrong | -0.5 per item |

`tolerance_pct` defaults to 1.0 (1% off) — set per case for harder/easier
matches.

## Limitations

- Requires manual `GEMINI_API_KEY` — no mock mode (test the real parser).
- No CI integration yet — runner is local-only. See "CI gate (future)" below.
- Each run costs Gemini tokens; budget accordingly when iterating prompts.
- Cases are hand-curated; coverage is best-effort, not exhaustive.

## CI gate (future)

Add to `.github/workflows/eval.yml` for automated regression catching:

```yaml
- name: Run eval harness
  run: |
    cd discord-monitor
    python -m eval.runner
  env:
    GEMINI_API_KEY: ${{ secrets.GEMINI_API_KEY }}
```

Threshold: overall >= 80% to pass.

## Running the scorer tests

The scorer is provider-independent — no API calls needed:

```bash
cd discord-monitor
python3 -m pytest eval/tests/ -v
```
