"""Configuration loader — reads config.yml into typed dataclasses."""
from __future__ import annotations

import os
import sys
from dataclasses import dataclass, field
from pathlib import Path

import yaml


@dataclass
class CdpConfig:
    host: str = "127.0.0.1"
    port: int = 9222
    reconnect_interval: int = 5
    max_reconnect_attempts: int = 0


@dataclass
class DiscordConfig:
    channel_ids: list[str] = field(default_factory=list)
    guild_ids: list[str] = field(default_factory=list)
    author_ids: list[str] = field(default_factory=list)


@dataclass
class ApiConfig:
    base_url: str = "http://localhost:8080"
    execute_endpoint: str = "/api/execute-signal"
    parse_endpoint: str = "/api/parse-signal"
    timeout: int = 10
    dry_run: bool = False
    multi_user_enabled: bool = False  # true = /api/broadcast-trade, false = /api/execute-trade
    api_key: str = ""  # Monitor API Key for authentication (X-Api-Key header)


@dataclass
class AiConfig:
    enabled: bool = False
    model: str = "gemini-2.0-flash"
    api_key_env: str = "GEMINI_API_KEY"
    timeout: int = 15
    max_retries: int = 3
    retry_delays: list[int] = field(default_factory=lambda: [2, 5, 10])


@dataclass
class LoggingConfig:
    level: str = "INFO"
    file: str | None = None


@dataclass
class AppConfig:
    cdp: CdpConfig = field(default_factory=CdpConfig)
    discord: DiscordConfig = field(default_factory=DiscordConfig)
    api: ApiConfig = field(default_factory=ApiConfig)
    ai: AiConfig = field(default_factory=AiConfig)
    logging: LoggingConfig = field(default_factory=LoggingConfig)

    def validate(self) -> None:
        """驗證必要的配置項目，啟動時呼叫。缺少必要設定時直接報錯退出。"""
        errors: list[str] = []

        if not self.discord.channel_ids:
            errors.append(
                "discord.channel_ids 不可為空 — "
                "請在 config.yml 或環境變數 DISCORD_CHANNEL_IDS 設定要監聽的頻道"
            )

        if not self.api.base_url or not self.api.base_url.strip():
            errors.append(
                "api.base_url 不可為空 — "
                "請在 config.yml 設定 Spring Boot API 的 base URL（例如 http://localhost:8080）"
            )

        if self.ai.enabled:
            api_key = os.environ.get(self.ai.api_key_env, "")
            if not api_key.strip():
                errors.append(
                    f"ai.enabled=true 但環境變數 {self.ai.api_key_env} 未設定或為空 — "
                    f"請設定 {self.ai.api_key_env} 環境變數，或將 ai.enabled 設為 false"
                )

        if errors:
            print("\n❌ 配置驗證失敗：", file=sys.stderr)
            for i, err in enumerate(errors, 1):
                print(f"  {i}. {err}", file=sys.stderr)
            print(file=sys.stderr)
            sys.exit(1)


def _env_list(env_var: str, default: list[str]) -> list[str]:
    """Read a comma-separated env var, fallback to YAML default."""
    val = os.environ.get(env_var, "")
    if val.strip():
        return [v.strip() for v in val.split(",") if v.strip()]
    return default


def load_config(path: str) -> AppConfig:
    """Load configuration from a YAML file."""
    config_path = Path(path)
    if not config_path.exists():
        raise FileNotFoundError(f"Config file not found: {path}")

    with open(config_path, "r", encoding="utf-8") as f:
        raw = yaml.safe_load(f) or {}

    cdp_raw = raw.get("cdp", {})
    discord_raw = raw.get("discord", {})
    api_raw = raw.get("api", {})
    ai_raw = raw.get("ai", {})
    logging_raw = raw.get("logging", {})

    return AppConfig(
        cdp=CdpConfig(
            host=cdp_raw.get("host", "127.0.0.1"),
            port=cdp_raw.get("port", 9222),
            reconnect_interval=cdp_raw.get("reconnect_interval", 5),
            max_reconnect_attempts=cdp_raw.get("max_reconnect_attempts", 0),
        ),
        discord=DiscordConfig(
            channel_ids=_env_list("DISCORD_CHANNEL_IDS", discord_raw.get("channel_ids", [])),
            guild_ids=_env_list("DISCORD_GUILD_IDS", discord_raw.get("guild_ids", [])),
            author_ids=_env_list("DISCORD_AUTHOR_IDS", discord_raw.get("author_ids", [])),
        ),
        api=ApiConfig(
            base_url=api_raw.get("base_url", "http://localhost:8080"),
            execute_endpoint=api_raw.get("execute_endpoint", "/api/execute-signal"),
            parse_endpoint=api_raw.get("parse_endpoint", "/api/parse-signal"),
            timeout=api_raw.get("timeout", 10),
            dry_run=api_raw.get("dry_run", False),
            multi_user_enabled=os.environ.get("MULTI_USER_ENABLED", str(api_raw.get("multi_user_enabled", False))).lower() == "true",
            api_key=os.environ.get("MONITOR_API_KEY", api_raw.get("api_key", "")),
        ),
        ai=AiConfig(
            enabled=ai_raw.get("enabled", False),
            model=ai_raw.get("model", "gemini-2.0-flash"),
            api_key_env=ai_raw.get("api_key_env", "GEMINI_API_KEY"),
            timeout=ai_raw.get("timeout", 15),
            max_retries=ai_raw.get("max_retries", 3),
            retry_delays=ai_raw.get("retry_delays", [2, 5, 10]),
        ),
        logging=LoggingConfig(
            level=logging_raw.get("level", "INFO"),
            file=logging_raw.get("file"),
        ),
    )
