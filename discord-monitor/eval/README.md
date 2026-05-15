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
- Each run costs Gemini tokens; budget accordingly when iterating prompts.
- Cases are hand-curated; coverage is best-effort, not exhaustive.

## CI gate

The eval runner is the only thing that can catch prompt regressions before
production. It MUST run automatically on the changes that can break it.

### When eval runs in CI

Triggered on any PR that touches:

- `discord-monitor/src/ai_parser.py`
- `discord-monitor/src/prompt_builder.py`
- `discord-monitor/eval/cases.jsonl`
- Any Java file under `src/main/java/com/trader/trading/service/SignalSourceService.java`
  (since this writes `custom_prompt` that the parser consumes)
- Any migration that touches `signal_sources` schema

### Workflow

`.github/workflows/eval.yml`:

```yaml
name: Eval (Signal Parser)

on:
  pull_request:
    paths:
      - 'discord-monitor/src/ai_parser.py'
      - 'discord-monitor/src/prompt_builder.py'
      - 'discord-monitor/eval/cases.jsonl'
      - 'src/main/java/com/trader/trading/service/SignalSourceService.java'
      - 'src/main/resources/db/migration/**signal_source**'

jobs:
  eval:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: { python-version: '3.10' }
      - run: pip install -r discord-monitor/requirements.txt
      - name: Run eval harness
        working-directory: discord-monitor
        run: python -m eval.runner --json eval-result.json
        env:
          GEMINI_API_KEY: ${{ secrets.GEMINI_API_KEY }}
      - uses: actions/upload-artifact@v4
        with: { name: eval-result, path: discord-monitor/eval-result.json }
```

### Pass criteria

| Criterion | Threshold | Hard / Soft |
|---|---|---|
| Overall score | ≥ 80% | Hard fail (exit 1) |
| Real-message subset (`category` starts with `messy_*`) | ≥ 90% | Soft (warn on PR) |
| Compound action category | 100% (action sequence must match) | Hard |
| Action type accuracy across all cases | ≥ 95% | Hard |

### When eval fails

1. **Do not merge** until score recovers.
2. If the prompt change is intentional and old cases are no longer valid:
   update `cases.jsonl` in the same PR with a justification in the PR
   description, then re-run.
3. If the change is to `signal_sources.custom_prompt` for a single source:
   add **at least one new case in `category: messy_<source_slug>`** that
   exercises the new override.

### Cost

Each full run = ~30 Gemini Flash calls ≈ $0.01–0.05 depending on case length.
Budget ≈ $10/month for typical PR volume.

### Local-only debugging

The runner stays usable locally without CI:

```bash
cd discord-monitor
python -m eval.runner --filter compound  # iterate fast on one category
```

## Running the scorer tests

The scorer is provider-independent — no API calls needed:

```bash
cd discord-monitor
python3 -m pytest eval/tests/ -v
```
