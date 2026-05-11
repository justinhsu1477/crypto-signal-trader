# Python ↔ Java Payload Fixtures

Snapshots of the exact JSON Python sends to Java's `/api/broadcast-trade` endpoint.

## Convention

Both Python and Java tests load fixtures from this directory.
**If you change the schema, update BOTH sides simultaneously.**

## Files

| File | Scenario | Notes |
|---|---|---|
| `text-entry.json` | Text-derived ENTRY signal | Baseline contract |
| `image-entry.json` | Image-derived ENTRY signal | Tests `source.attachment.sha256` audit chain |
| `compound-close-half.json` | First half of compound action (CLOSE 50%) | Note `__close` suffix on message_id |
| `compound-movesl-breakeven.json` | Second half of compound action | No `new_stop_loss` field → Java auto-breakeven |

## Used by

- Java: `src/test/java/com/trader/contract/PythonPayloadContractTest.java`
- Python: `discord-monitor/tests/test_payload_contract.py`
