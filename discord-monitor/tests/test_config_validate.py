"""Tests for AppConfig.validate() + QueueConfig — 啟動驗證 + 佇列設定。"""
from __future__ import annotations

import os
import tempfile
import pytest

from src.config import AppConfig, DiscordConfig, ApiConfig, AiConfig, QueueConfig, load_config


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


class TestQueueConfig:
    """QueueConfig 預設值 + YAML 載入測試。"""

    def test_default_values(self):
        """QueueConfig 預設值正確。"""
        cfg = QueueConfig()
        assert cfg.enabled is True
        assert cfg.queue_dir == "data"
        assert cfg.max_size == 100
        assert cfg.max_age_hours == 24
        assert cfg.max_replay_attempts == 5

    def test_custom_values(self):
        """QueueConfig 可自訂所有欄位。"""
        cfg = QueueConfig(
            enabled=False,
            queue_dir="/tmp/queue",
            max_size=50,
            max_age_hours=12,
            max_replay_attempts=3,
        )
        assert cfg.enabled is False
        assert cfg.queue_dir == "/tmp/queue"
        assert cfg.max_size == 50
        assert cfg.max_age_hours == 12
        assert cfg.max_replay_attempts == 3

    def test_appconfig_includes_queue(self):
        """AppConfig 包含 QueueConfig 且預設啟用。"""
        cfg = AppConfig()
        assert cfg.queue is not None
        assert cfg.queue.enabled is True

    def test_load_config_with_queue_section(self, monkeypatch):
        """YAML 含 queue 區塊 → QueueConfig 正確載入。"""
        monkeypatch.delenv("DISCORD_CHANNEL_IDS", raising=False)
        monkeypatch.delenv("DISCORD_GUILD_IDS", raising=False)
        monkeypatch.delenv("DISCORD_AUTHOR_IDS", raising=False)
        monkeypatch.delenv("DISCORD_IGNORE_KEYWORDS", raising=False)
        monkeypatch.delenv("MULTI_USER_ENABLED", raising=False)
        monkeypatch.delenv("MONITOR_API_KEY", raising=False)

        yaml_content = """\
cdp:
  host: "127.0.0.1"
  port: 9222
discord:
  channel_ids: ["ch1"]
api:
  base_url: "http://localhost:8080"
ai:
  enabled: false
queue:
  enabled: false
  queue_dir: "custom_data"
  max_size: 50
  max_age_hours: 12
  max_replay_attempts: 3
"""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".yml", delete=False) as f:
            f.write(yaml_content)
            f.flush()
            cfg = load_config(f.name)

        assert cfg.queue.enabled is False
        assert cfg.queue.queue_dir == "custom_data"
        assert cfg.queue.max_size == 50
        assert cfg.queue.max_age_hours == 12
        assert cfg.queue.max_replay_attempts == 3

        os.unlink(f.name)

    def test_load_config_without_queue_section(self, monkeypatch):
        """YAML 無 queue 區塊 → 使用預設值。"""
        monkeypatch.delenv("DISCORD_CHANNEL_IDS", raising=False)
        monkeypatch.delenv("DISCORD_GUILD_IDS", raising=False)
        monkeypatch.delenv("DISCORD_AUTHOR_IDS", raising=False)
        monkeypatch.delenv("DISCORD_IGNORE_KEYWORDS", raising=False)
        monkeypatch.delenv("MULTI_USER_ENABLED", raising=False)
        monkeypatch.delenv("MONITOR_API_KEY", raising=False)

        yaml_content = """\
cdp:
  host: "127.0.0.1"
discord:
  channel_ids: ["ch1"]
api:
  base_url: "http://localhost:8080"
ai:
  enabled: false
"""
        with tempfile.NamedTemporaryFile(mode="w", suffix=".yml", delete=False) as f:
            f.write(yaml_content)
            f.flush()
            cfg = load_config(f.name)

        # 預設值
        assert cfg.queue.enabled is True
        assert cfg.queue.max_size == 100

        os.unlink(f.name)
