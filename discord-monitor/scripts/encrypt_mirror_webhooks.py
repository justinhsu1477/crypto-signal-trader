#!/usr/bin/env python3
"""
Encrypt mirror webhook URLs for `signal_sources.mirror_webhook_url` column.

Matches Java AesEncryptionUtil format exactly:
  - AES-256-GCM
  - Key = first 32 bytes of `encryption.aes-key` (UTF-8)
  - IV = 12 random bytes per encryption
  - Tag = 128 bits (16 bytes)
  - Output = Base64(IV[12] + ciphertext + tag[16])

Usage:
  ENCRYPTION_AES_KEY="$(grep encryption.aes-key application.yml | awk '{print $2}')" \
    python3 encrypt_mirror_webhooks.py < mirror_webhooks_input.csv > mirror_updates.sql

Input CSV format (pipe-separated, lines starting with # are comments):
  channel_id|webhook_url|source_name

Example input:
  # source_name in comment for readability — script uses channel_id to match signal_sources row
  1505168305185357956|https://discord.com/api/webhooks/.../...|陳哥
  1505168385023934588|https://discord.com/api/webhooks/.../...|三馬哥

Output: SQL UPDATE statements ready for psql, plus a sanity SELECT at end.

Requires: pip install cryptography
"""
from __future__ import annotations

import base64
import csv
import os
import sys
from datetime import datetime
from typing import Iterable

try:
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:
    sys.stderr.write(
        "ERROR: cryptography library not installed.\n"
        "Run: pip install cryptography\n"
    )
    sys.exit(1)


def encrypt_aes_gcm(plaintext: str, aes_key_str: str) -> str:
    """AES-256-GCM encrypt — output matches Java AesEncryptionUtil format.

    Java code reference:
        SecretKeySpec keySpec = new SecretKeySpec(aesKey.getBytes(), 0, 32, "AES");
        byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv));
        byte[] ct = cipher.doFinal(plaintext.getBytes());  // includes 16-byte tag at end
        // Output: Base64(iv + ct)

    Python AESGCM.encrypt returns ciphertext+tag concatenated (same as Java doFinal).
    """
    key_bytes = aes_key_str.encode("utf-8")[:32]
    if len(key_bytes) < 32:
        raise ValueError(
            f"AES key too short: need 32 bytes, got {len(key_bytes)}"
        )
    aesgcm = AESGCM(key_bytes)
    iv = os.urandom(12)
    ct_with_tag = aesgcm.encrypt(iv, plaintext.encode("utf-8"), None)
    return base64.b64encode(iv + ct_with_tag).decode("ascii")


def parse_input(lines: Iterable[str]) -> list[tuple[str, str, str]]:
    """Parse pipe-separated input. Returns list of (channel_id, url, source_name)."""
    rows: list[tuple[str, str, str]] = []
    for line in lines:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("|")
        if len(parts) < 2:
            sys.stderr.write(f"WARN: skipping malformed line: {line!r}\n")
            continue
        channel_id = parts[0].strip()
        url = parts[1].strip()
        source_name = parts[2].strip() if len(parts) >= 3 else ""
        if not channel_id or not url:
            sys.stderr.write(f"WARN: skipping empty channel_id/url: {line!r}\n")
            continue
        if not url.startswith("https://discord.com/api/webhooks/"):
            sys.stderr.write(
                f"WARN: URL does not look like a Discord webhook: {url!r}\n"
            )
        rows.append((channel_id, url, source_name))
    return rows


def main() -> int:
    aes_key = os.environ.get("ENCRYPTION_AES_KEY", "")
    if not aes_key:
        sys.stderr.write(
            "ERROR: ENCRYPTION_AES_KEY env var not set.\n"
            "Set it to the same value as `encryption.aes-key` in application.yml\n"
            "(must be at least 32 bytes UTF-8).\n"
        )
        return 1
    if len(aes_key.encode("utf-8")) < 32:
        sys.stderr.write(
            f"ERROR: ENCRYPTION_AES_KEY too short ({len(aes_key.encode('utf-8'))} bytes); "
            f"need at least 32 bytes.\n"
        )
        return 1

    rows = parse_input(sys.stdin)
    if not rows:
        sys.stderr.write("ERROR: no valid rows in input.\n")
        return 1

    # Emit SQL
    timestamp = datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S UTC")
    print(f"-- Mirror webhook bulk update — generated {timestamp}")
    print(f"-- {len(rows)} signal_sources rows will be updated.")
    print(f"-- ⚠️  Webhook URLs encrypted with AES-256-GCM (Java AesEncryptionUtil compatible).")
    print(f"-- ⚠️  Run inside a TRANSACTION to allow rollback if the count looks wrong.")
    print()
    print("BEGIN;")
    print()

    for channel_id, url, source_name in rows:
        encrypted = encrypt_aes_gcm(url, aes_key)
        comment = f"-- {source_name} (channel {channel_id})" if source_name else f"-- channel {channel_id}"
        print(comment)
        # UPDATE by channel_id (more reliable than guessing source id).
        # If signal_sources doesn't have a row with this channel_id, the UPDATE is a no-op (0 rows affected) — we'll spot that in the sanity SELECT.
        print(
            "UPDATE signal_sources SET "
            f"mirror_webhook_url = '{encrypted}', "
            "mirror_enabled = TRUE, "
            "updated_at = NOW() "
            f"WHERE channel_id = '{channel_id}';"
        )
        print()

    # Sanity SELECT at the end
    print("-- Sanity check: list all sources with mirror enabled. Should show the rows above.")
    print(
        "SELECT id, name, display_name, channel_id, mirror_enabled, "
        "LEFT(mirror_webhook_url, 24) || '...' AS mirror_url_preview "
        "FROM signal_sources "
        "WHERE channel_id IN ("
        + ", ".join(f"'{r[0]}'" for r in rows)
        + ") ORDER BY name;"
    )
    print()
    print("-- After verifying the SELECT looks right, run: COMMIT;")
    print("-- If something looks wrong, run: ROLLBACK;")
    print()

    sys.stderr.write(f"✓ Generated SQL for {len(rows)} mirror webhook updates.\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
