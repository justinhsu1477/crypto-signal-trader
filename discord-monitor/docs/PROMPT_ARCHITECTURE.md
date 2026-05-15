# Signal Parser Prompt Architecture

The Discord signal parser prompt is intentionally composed from four layers:

1. **Base schema**
   - Defines the JSON output contract.
   - This layer is invariant. Source-specific rules must not override schema keys,
     JSON-only output, or required action semantics.

2. **Action rules**
   - Generic cross-source rules for `ENTRY`, `CLOSE`, `MOVE_SL`, `CANCEL`,
     `DCA`, `INFO`, and compound actions.
   - This is where shared trading language belongs.

3. **Source-specific override**
   - Optional `custom_prompt` from the Java `signal_sources` config.
   - Delivered to the Python monitor through gRPC source metadata.
   - Intended for source dialects, for example:
     - a channel-specific meaning of "保護"
     - image layout hints
     - analyst-specific shorthand
   - It is inserted after generic action rules and before examples, so it can
     refine interpretation without breaking the base schema.

4. **Eval examples**
   - Few-shot examples embedded in the system prompt.
   - Regression cases also live in `discord-monitor/eval/cases.jsonl`.
   - When adding or changing a source override, add at least one eval case that
     demonstrates the expected behavior.

## Runtime Flow

```text
SignalSourceConfig.customPrompt
  -> MonitorConfig gRPC SourceConfig.custom_prompt
  -> SignalRouter source.custom_prompt
  -> AiSignalParser.build_system_prompt(source_prompt, source_name)
  -> Gemini system_instruction
```

Both text and image parsing paths pass the same source override into the parser.

## Precedence

The effective precedence is:

```text
Base schema contract > source override safety limits > source dialect hints > generic examples
```

In practice:

- A source override may clarify what a phrase means for one channel.
- It may not ask the model to output non-JSON, omit required fields, or invent
  unsupported required schema fields.
- If the override changes behavior, add an eval case and run:

```bash
cd discord-monitor
python -m eval.runner --filter <category>
```

For provider-independent tests:

```bash
cd discord-monitor
python -m pytest tests eval/tests
```

## Safety Constraints on `custom_prompt`

The source override is a trust boundary. An admin who can set `custom_prompt`
effectively changes how the LLM interprets signals for all subscribed users of
that source. The following constraints MUST be enforced on the write path
(currently `AdminSignalSourceController` → `SignalSourceService`):

### Hard limits

| Constraint | Value | Reason |
|---|---|---|
| Max length | 1500 chars | Caps token cost per Gemini call across the broadcast fan-out |
| Encoding | UTF-8, no control chars except `\n` `\t` | Blocks U+202E and zero-width tricks that bypass keyword filters |
| Forbidden markers | `## 規則`, `## 範例`, `## 複合動作識別`, `## Rules`, `## Examples` | These collide with `from_legacy_prompt()` section markers — admin overrides must not re-partition the prompt |
| Forbidden phrases | `忽略以上`, `ignore previous`, `disregard the above`, `output plain text`, `不要輸出 JSON`, `respond with`, `respond in` | Direct prompt-injection patterns; reject at write time, do not rely on LLM to obey base-schema clause |
| Forbidden schema verbs | `add field`, `新增欄位`, `output additional`, `output extra` | Schema is invariant; new fields must come from a parser release, not admin config |

### Soft limits (warn but allow)

- Override longer than 800 chars → log warning at write time. Long overrides
  usually mean "rules should be promoted to action_rules" — flag for review.
- Override that contains numeric thresholds (entry/stop/leverage) → log warning.
  Per-trade thresholds belong in `signal_sources.risk_multiplier` or
  `UserTradeSettings`, not in the prompt.

### Audit chain

Every successful parse that used a non-empty `custom_prompt` MUST record the
following on the resulting `BroadcastLog` / `Trade`:

- `effective_custom_prompt_sha256` — first 16 hex chars of SHA-256 over the
  exact override string used, including the `來源：{name}` header.
- `custom_prompt_version` — monotonic counter on the source, bumped on every
  successful write. Lets us replay "what prompt did this trade run against".

This satisfies the "完整操作日誌" guarantee already claimed in
`docs/legal-risk-analysis.md` §6.1, which today is only true for the global
prompt (`prompt_version`).

### Sanitization placement

| Stage | Responsibility |
|---|---|
| Admin write (Java `SignalSourceService.setCustomPrompt`) | Length cap, forbidden markers/phrases, control-char strip, audit log |
| gRPC push (Java `MonitorConfigService`) | Trusted-internal: re-validate length only, do NOT re-check content (single source of truth is write-side) |
| Python `prompt_builder._render_source_override` | Wrap in fixed safety header (`不可覆蓋輸出 JSON Schema...`) — defense in depth, not primary defense |

### Who can write `custom_prompt`?

See `docs/admin-permission-model.md` for the role matrix. Today, every authn'd
admin can modify any source's override; that is acceptable for the current
single-admin deployment but MUST tighten before multi-admin SaaS.

