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
    ignore_keywords: list[str] = field(default_factory=list)  # 內容黑名單（一對一等非交易訊號）


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
    model: str = "gemini-2.5-flash"  # gemini-2.0-flash 已下架; 可經 gRPC 由 server 覆蓋
    api_key_env: str = "GEMINI_API_KEY"
    timeout: int = 15
    max_retries: int = 3
    retry_delays: list[int] = field(default_factory=lambda: [2, 5, 10])


@dataclass
class LoggingConfig:
    level: str = "INFO"
    file: str | None = None


@dataclass
class GrpcConfig:
    """gRPC 即時配置推送設定。連線到 Java Spring Boot gRPC Server 接收頻道設定變更。"""
    enabled: bool = False
    target: str = ""               # Java gRPC Server 位址，例如 "grpc.hook-fi.com:443"
    use_tls: bool = False          # 走 Caddy TLS 代理時需設為 True（生產環境設 True）
    reconnect_interval: int = 5    # 重連間隔（秒）


@dataclass
class QueueConfig:
    """失敗訊號本地佇列設定。API 當機時暫存訊號，恢復後自動重播。"""
    enabled: bool = True           # 啟用失敗訊號佇列
    queue_dir: str = "data"        # 佇列檔案目錄
    max_size: int = 100            # 最大佇列深度
    max_age_hours: int = 24        # 超過此時數自動過期
    max_replay_attempts: int = 5   # 每個訊號最多重播次數


@dataclass
class ImageSignalConfig:
    """圖片訊號解析設定 — 處理陳哥等用截圖發訊號的訊息源。

    - enabled: 主開關，false 時整條 image path 不會啟用，現有文字流不受影響
    - dry_run: true 時走完解析流程但不送 Java（用於 shadow mode 驗證）
    - allowed_symbols: 白名單，目前僅 BTCUSDT，將來想擴幣只改 config 不改 code
    - max_image_bytes: 下載圖片大小上限，超過 skip 避免 LLM 超大圖
    """
    enabled: bool = False
    dry_run: bool = True
    allowed_symbols: list[str] = field(default_factory=lambda: ["BTCUSDT"])
    max_image_bytes: int = 5 * 1024 * 1024  # 5 MB


@dataclass
class AppConfig:
    cdp: CdpConfig = field(default_factory=CdpConfig)
    discord: DiscordConfig = field(default_factory=DiscordConfig)
    api: ApiConfig = field(default_factory=ApiConfig)
    ai: AiConfig = field(default_factory=AiConfig)
    logging: LoggingConfig = field(default_factory=LoggingConfig)
    queue: QueueConfig = field(default_factory=QueueConfig)
    grpc: GrpcConfig = field(default_factory=GrpcConfig)
    image_signal: ImageSignalConfig = field(default_factory=ImageSignalConfig)

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
    queue_raw = raw.get("queue", {})
    grpc_raw = raw.get("grpc", {})
    image_raw = raw.get("image_signal", {})

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
            ignore_keywords=_env_list("DISCORD_IGNORE_KEYWORDS", discord_raw.get("ignore_keywords", [])),
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
        queue=QueueConfig(
            enabled=queue_raw.get("enabled", True),
            queue_dir=queue_raw.get("queue_dir", "data"),
            max_size=queue_raw.get("max_size", 100),
            max_age_hours=queue_raw.get("max_age_hours", 24),
            max_replay_attempts=queue_raw.get("max_replay_attempts", 5),
        ),
        grpc=GrpcConfig(
            enabled=os.environ.get("GRPC_ENABLED", str(grpc_raw.get("enabled", False))).lower() == "true",
            target=os.environ.get("GRPC_TARGET", grpc_raw.get("target", "")),
            use_tls=os.environ.get("GRPC_USE_TLS", str(grpc_raw.get("use_tls", False))).lower() == "true",
            reconnect_interval=grpc_raw.get("reconnect_interval", 5),
        ),
        image_signal=ImageSignalConfig(
            enabled=image_raw.get("enabled", False),
            dry_run=image_raw.get("dry_run", True),
            allowed_symbols=image_raw.get("allowed_symbols", ["BTCUSDT"]),
            max_image_bytes=image_raw.get("max_image_bytes", 5 * 1024 * 1024),
        ),
    )
