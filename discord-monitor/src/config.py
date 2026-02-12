"""Configuration loader — reads config.yml into typed dataclasses."""
from __future__ import annotations

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


@dataclass
class AiConfig:
    enabled: bool = False
    model: str = "gemini-2.0-flash"
    api_key_env: str = "GEMINI_API_KEY"
    timeout: int = 15


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
            channel_ids=discord_raw.get("channel_ids", []),
            guild_ids=discord_raw.get("guild_ids", []),
            author_ids=discord_raw.get("author_ids", []),
        ),
        api=ApiConfig(
            base_url=api_raw.get("base_url", "http://localhost:8080"),
            execute_endpoint=api_raw.get("execute_endpoint", "/api/execute-signal"),
            parse_endpoint=api_raw.get("parse_endpoint", "/api/parse-signal"),
            timeout=api_raw.get("timeout", 10),
            dry_run=api_raw.get("dry_run", False),
        ),
        ai=AiConfig(
            enabled=ai_raw.get("enabled", False),
            model=ai_raw.get("model", "gemini-2.0-flash"),
            api_key_env=ai_raw.get("api_key_env", "GEMINI_API_KEY"),
            timeout=ai_raw.get("timeout", 15),
        ),
        logging=LoggingConfig(
            level=logging_raw.get("level", "INFO"),
            file=logging_raw.get("file"),
        ),
    )
