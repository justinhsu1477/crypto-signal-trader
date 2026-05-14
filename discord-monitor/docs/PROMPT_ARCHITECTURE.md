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

