"""Tests for AppConfig.validate() — 啟動驗證。"""
from __future__ import annotations

import os
import pytest

from src.config import AppConfig, DiscordConfig, ApiConfig, AiConfig


class TestConfigValidate:
    """AppConfig.validate() 單元測試。"""

    def _base_config(self, **overrides) -> AppConfig:
        """建立一個通過驗證的最小 config。"""
        cfg = AppConfig(
            discord=DiscordConfig(channel_ids=["123456"]),
            api=ApiConfig(base_url="http://localhost:8080"),
            ai=AiConfig(enabled=False),
        )
        for k, v in overrides.items():
            setattr(cfg, k, v)
        return cfg

    def test_valid_config_passes(self):
        """完整的 config — 不拋例外。"""
        cfg = self._base_config()
        cfg.validate()  # 不應拋出

    def test_empty_channel_ids_fails(self):
        """channel_ids 為空 — sys.exit(1)。"""
        cfg = self._base_config(discord=DiscordConfig(channel_ids=[]))
        with pytest.raises(SystemExit) as exc_info:
            cfg.validate()
        assert exc_info.value.code == 1

    def test_empty_base_url_fails(self):
        """base_url 為空字串 — sys.exit(1)。"""
        cfg = self._base_config(api=ApiConfig(base_url=""))
        with pytest.raises(SystemExit) as exc_info:
            cfg.validate()
        assert exc_info.value.code == 1

    def test_whitespace_base_url_fails(self):
        """base_url 只有空白 — sys.exit(1)。"""
        cfg = self._base_config(api=ApiConfig(base_url="   "))
        with pytest.raises(SystemExit) as exc_info:
            cfg.validate()
        assert exc_info.value.code == 1

    def test_ai_enabled_without_key_fails(self, monkeypatch):
        """ai.enabled=true 但 GEMINI_API_KEY 未設 — sys.exit(1)。"""
        monkeypatch.delenv("GEMINI_API_KEY", raising=False)
        cfg = self._base_config(ai=AiConfig(enabled=True, api_key_env="GEMINI_API_KEY"))
        with pytest.raises(SystemExit) as exc_info:
            cfg.validate()
        assert exc_info.value.code == 1

    def test_ai_enabled_with_key_passes(self, monkeypatch):
        """ai.enabled=true 且 GEMINI_API_KEY 有值 — 通過。"""
        monkeypatch.setenv("GEMINI_API_KEY", "test-key-123")
        cfg = self._base_config(ai=AiConfig(enabled=True, api_key_env="GEMINI_API_KEY"))
        cfg.validate()  # 不應拋出

    def test_ai_disabled_without_key_passes(self, monkeypatch):
        """ai.enabled=false — 不檢查 GEMINI_API_KEY。"""
        monkeypatch.delenv("GEMINI_API_KEY", raising=False)
        cfg = self._base_config(ai=AiConfig(enabled=False))
        cfg.validate()  # 不應拋出

    def test_multiple_errors_reported(self, monkeypatch):
        """多個錯誤同時存在 — 一次全部報出。"""
        monkeypatch.delenv("GEMINI_API_KEY", raising=False)
        cfg = AppConfig(
            discord=DiscordConfig(channel_ids=[]),
            api=ApiConfig(base_url=""),
            ai=AiConfig(enabled=True, api_key_env="GEMINI_API_KEY"),
        )
        with pytest.raises(SystemExit) as exc_info:
            cfg.validate()
        assert exc_info.value.code == 1
