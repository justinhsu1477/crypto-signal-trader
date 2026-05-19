#!/usr/bin/env python3
"""
Generate idempotent SQL for importing CDP source -> Discord mirror target mappings.

Input CSV headers:
  source_guild_id,source_channel_id,source_channel_name,source_category_name,
  target_guild_id,target_channel_id,target_channel_name,target_category_name,webhook_url

The generated SQL:
  - reuses an existing signal_sources row by source channel_id
  - creates missing sources as ASSIGNED + SHADOW + enabled
  - enables mirror on imported sources
  - upserts one signal_source_mirror_targets row per target channel
  - stores webhook URLs encrypted with the same AES-GCM format as AesEncryptionUtil
"""
from __future__ import annotations

import base64
import csv
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


MAX_NAME = 100
SOURCE_GUILD_DEFAULT = "1004707886657699901"


def encrypt_aes_gcm(plaintext: str, aes_key: str) -> str:
    key_bytes = aes_key.encode("utf-8")[:32]
    if len(key_bytes) < 32:
        raise ValueError("AES key must be at least 32 bytes")
    iv = os.urandom(12)
    encrypted = AESGCM(key_bytes).encrypt(iv, plaintext.encode("utf-8"), None)
    return base64.b64encode(iv + encrypted).decode("ascii")


def sql(value: str | None) -> str:
    if value is None:
        return "NULL"
    return "'" + str(value).replace("'", "''") + "'"


def compact(value: str | None, fallback: str) -> str:
    text = (value or "").strip() or fallback
    return text[:MAX_NAME]


def read_rows(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as fh:
        rows = [dict(row) for row in csv.DictReader(fh)]
    required = {
        "source_channel_id",
        "source_channel_name",
        "target_guild_id",
        "target_channel_id",
        "target_channel_name",
        "webhook_url",
    }
    missing = required - set(rows[0].keys() if rows else [])
    if missing:
        raise ValueError(f"missing required CSV headers: {sorted(missing)}")
    return [
        row for row in rows
        if row.get("source_channel_id") and row.get("target_channel_id") and row.get("webhook_url")
    ]


def emit_sql(rows: list[dict[str, str]], aes_key: str) -> None:
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC")
    print(f"-- CDP mirror target import generated at {now}")
    print(f"-- rows: {len(rows)}")
    print("BEGIN;")
    print()

    for row in rows:
        source_channel_id = row["source_channel_id"].strip()
        source_guild_id = (row.get("source_guild_id") or SOURCE_GUILD_DEFAULT).strip()
        source_name = compact(row.get("source_channel_name"), source_channel_id)
        source_category = (row.get("source_category_name") or "").strip()
        target_guild_id = row["target_guild_id"].strip()
        target_channel_id = row["target_channel_id"].strip()
        target_name = compact(row.get("target_channel_name"), target_channel_id)
        target_category = (row.get("target_category_name") or "").strip()
        encrypted_webhook = encrypt_aes_gcm(row["webhook_url"].strip(), aes_key)
        description = (
            f"CDP mirror import: {source_category}/{source_name} "
            f"-> {target_category}/{target_name}"
        )[:500]

        print(f"-- {source_name} ({source_channel_id}) -> {target_name} ({target_channel_id})")
        print("DO $$")
        print("DECLARE")
        print("    v_source_id BIGINT;")
        print("BEGIN")
        print(
            "    SELECT id INTO v_source_id "
            "FROM signal_sources "
            f"WHERE channel_id = {sql(source_channel_id)} "
            "ORDER BY id LIMIT 1;"
        )
        print("    IF v_source_id IS NULL THEN")
        print("        INSERT INTO signal_sources (")
        print("            name, display_name, channel_id, guild_id, description,")
        print("            routing_mode, trade_mode, risk_multiplier, custom_prompt,")
        print("            custom_prompt_version, mirror_enabled, paper_trading_enabled,")
        print("            enabled, created_at, updated_at")
        print("        ) VALUES (")
        print(
            "            "
            + ", ".join([
                sql(source_name),
                sql(source_name),
                sql(source_channel_id),
                sql(source_guild_id),
                sql(description),
                "'ASSIGNED'",
                "'SHADOW'",
                "1.0",
                "''",
                "0",
                "TRUE",
                "FALSE",
                "TRUE",
                "NOW()",
                "NOW()",
            ])
        )
        print("        ) RETURNING id INTO v_source_id;")
        print("    ELSE")
        print("        UPDATE signal_sources")
        print("        SET mirror_enabled = TRUE, updated_at = NOW()")
        print("        WHERE id = v_source_id;")
        print("    END IF;")
        print()
        print("    INSERT INTO signal_source_mirror_targets (")
        print("        source_id, target_guild_id, target_channel_id, label,")
        print("        mirror_webhook_url, enabled, created_at, updated_at")
        print("    ) VALUES (")
        print(
            "        "
            + ", ".join([
                "v_source_id",
                sql(target_guild_id),
                sql(target_channel_id),
                sql(target_name),
                sql(encrypted_webhook),
                "TRUE",
                "NOW()",
                "NOW()",
            ])
        )
        print("    ) ON CONFLICT (source_id, target_channel_id) DO UPDATE SET")
        print("        target_guild_id = EXCLUDED.target_guild_id,")
        print("        label = EXCLUDED.label,")
        print("        mirror_webhook_url = EXCLUDED.mirror_webhook_url,")
        print("        enabled = TRUE,")
        print("        updated_at = NOW();")
        print("END $$;")
        print()

    print("COMMIT;")
    print()
    print("-- Verification")
    print(
        "SELECT COUNT(*) AS imported_targets "
        "FROM signal_source_mirror_targets "
        f"WHERE target_guild_id = {sql(rows[0]['target_guild_id'].strip())};"
    )


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: import_signal_source_mirror_targets.py mappings.csv", file=sys.stderr)
        return 2
    aes_key = os.environ.get("AES_ENCRYPTION_KEY", "")
    if len(aes_key.encode("utf-8")) < 32:
        print("AES_ENCRYPTION_KEY is missing or shorter than 32 bytes", file=sys.stderr)
        return 2
    rows = read_rows(Path(sys.argv[1]))
    if not rows:
        print("no importable rows found", file=sys.stderr)
        return 2
    emit_sql(rows, aes_key)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
