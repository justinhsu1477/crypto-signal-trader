"""Unit tests for _attach_custom_prompt_audit — signals 表 audit chain 的 Python 端."""
from __future__ import annotations

import hashlib

import pytest

from src.signal_router import _attach_custom_prompt_audit


def _sha16(s: str) -> str:
    return hashlib.sha256(s.encode("utf-8")).hexdigest()[:16]


class TestAttachCustomPromptAudit:
    """audit chain Python 端的核心：把 source 的 prompt 識別碼塞進 payload。"""

    def test_source_with_prompt_attaches_both_fields(self):
        payload: dict = {"action": "ENTRY", "symbol": "BTCUSDT"}
        source = {
            "custom_prompt": "陳哥用「保護」=移動 SL",
            "custom_prompt_version": 3,
        }
        _attach_custom_prompt_audit(payload, source)

        assert payload["effective_custom_prompt_version"] == 3
        assert payload["effective_custom_prompt_sha256"] == _sha16("陳哥用「保護」=移動 SL")

    def test_empty_prompt_skips_both_fields(self):
        """source 設了但 custom_prompt 是空字串 — 視為沒設，不送 audit 欄位。"""
        payload: dict = {"action": "ENTRY"}
        source = {"custom_prompt": "", "custom_prompt_version": 0}
        _attach_custom_prompt_audit(payload, source)

        assert "effective_custom_prompt_version" not in payload
        assert "effective_custom_prompt_sha256" not in payload

    def test_no_source_is_noop(self):
        payload: dict = {"action": "ENTRY"}
        _attach_custom_prompt_audit(payload, None)
        assert "effective_custom_prompt_version" not in payload

    def test_missing_version_defaults_to_zero(self):
        """有 prompt 沒 version（gRPC 來不及推） — 還是要送 sha，version 用 0。"""
        payload: dict = {"action": "ENTRY"}
        source = {"custom_prompt": "some rule"}
        _attach_custom_prompt_audit(payload, source)

        assert payload["effective_custom_prompt_version"] == 0
        assert payload["effective_custom_prompt_sha256"] == _sha16("some rule")

    def test_sha256_is_deterministic(self):
        """同樣的 prompt 一定算出同樣的 hash — Java 那邊靠這個對齊。"""
        p1: dict = {}
        p2: dict = {}
        source = {"custom_prompt": "test rule\n  with  spaces", "custom_prompt_version": 5}

        _attach_custom_prompt_audit(p1, source)
        _attach_custom_prompt_audit(p2, source)

        assert p1["effective_custom_prompt_sha256"] == p2["effective_custom_prompt_sha256"]
        assert len(p1["effective_custom_prompt_sha256"]) == 16

    def test_different_prompts_differ(self):
        p1: dict = {}
        p2: dict = {}
        _attach_custom_prompt_audit(p1, {"custom_prompt": "rule A", "custom_prompt_version": 1})
        _attach_custom_prompt_audit(p2, {"custom_prompt": "rule B", "custom_prompt_version": 1})
        assert p1["effective_custom_prompt_sha256"] != p2["effective_custom_prompt_sha256"]

    def test_version_is_coerced_to_int(self):
        """version 從 protobuf 過來可能是各種數值型別，statically 確保 int."""
        payload: dict = {}
        source = {"custom_prompt": "x", "custom_prompt_version": 7.0}
        _attach_custom_prompt_audit(payload, source)
        assert payload["effective_custom_prompt_version"] == 7
        assert isinstance(payload["effective_custom_prompt_version"], int)

    def test_does_not_overwrite_existing_unrelated_keys(self):
        payload: dict = {"action": "ENTRY", "symbol": "BTCUSDT", "prompt_version": 12}
        source = {"custom_prompt": "p", "custom_prompt_version": 4}
        _attach_custom_prompt_audit(payload, source)
        assert payload["action"] == "ENTRY"
        assert payload["symbol"] == "BTCUSDT"
        assert payload["prompt_version"] == 12  # 全局 SYSTEM_PROMPT 的 version，不可被覆蓋
