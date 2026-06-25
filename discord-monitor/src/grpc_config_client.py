"""gRPC client for receiving real-time config updates from Java Spring Boot.

使用 Server Streaming 即時接收 Admin 推送的頻道設定變更，
自動更新 SignalRouter 的 channel_ids 實現熱切換。
"""
from __future__ import annotations

import asyncio
import logging

import grpc

from .generated import monitor_config_pb2 as pb2
from .generated import monitor_config_pb2_grpc as pb2_grpc

logger = logging.getLogger(__name__)


class GrpcConfigClient:
    """Async gRPC client for MonitorConfigService.

    功能：
    1. get_initial_config() — 啟動時 Unary RPC 取得初始設定
    2. _watch_loop() — 背景 Server Streaming，即時接收設定變更
    3. _apply_config() — 熱更新 SignalRouter 的過濾條件
    """

    def __init__(self, grpc_target: str, api_key: str, signal_router, *, use_tls: bool = False):
        self.grpc_target = grpc_target  # e.g. "your-domain.com:9443"
        self.api_key = api_key
        self.signal_router = signal_router
        self.use_tls = use_tls
        self._task: asyncio.Task | None = None
        self._should_stop = False

    def start(self):
        """啟動背景 config watch task."""
        self._should_stop = False
        self._task = asyncio.create_task(self._watch_loop())
        logger.info("gRPC config watch 已啟動, target=%s", self.grpc_target)

    async def stop(self):
        """停止背景 task."""
        self._should_stop = True
        if self._task and not self._task.done():
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
        logger.info("gRPC config watch 已停止")

    def _make_channel(self):
        """建立 gRPC channel（TLS 或 insecure）。"""
        if self.use_tls:
            credentials = grpc.ssl_channel_credentials()
            return grpc.aio.secure_channel(self.grpc_target, credentials)
        return grpc.aio.insecure_channel(self.grpc_target)

    def _make_metadata(self):
        """建立帶 API Key 的 gRPC metadata."""
        metadata = []
        if self.api_key:
            metadata.append(("x-api-key", self.api_key))
        return metadata

    async def get_initial_config(self) -> bool:
        """Unary RPC: 啟動時取得初始頻道設定.

        Returns:
            True 如果成功取得設定，False 如果失敗（將使用 config.yml 的設定）
        """
        try:
            async with self._make_channel() as channel:
                stub = pb2_grpc.MonitorConfigServiceStub(channel)
                response = await stub.GetConfig(
                    pb2.GetConfigRequest(),
                    metadata=self._make_metadata(),
                    timeout=10,
                )
                config = response.config
                if config.channel_ids:
                    self._apply_config(config, "initial_sync")
                    return True
                else:
                    logger.info("gRPC 初始設定為空，使用 config.yml 的設定")
                    return False
        except Exception as e:
            logger.warning("gRPC GetConfig 失敗: %s (將使用 config.yml 的設定)", e)
            return False

    async def _watch_loop(self):
        """Server Streaming: 持續監聽設定變更，斷線自動重連."""
        retry_delay = 5
        max_delay = 60

        while not self._should_stop:
            try:
                async with self._make_channel() as channel:
                    stub = pb2_grpc.MonitorConfigServiceStub(channel)
                    stream = stub.WatchConfig(
                        pb2.WatchConfigRequest(),
                        metadata=self._make_metadata(),
                    )
                    logger.info("gRPC WatchConfig stream 已連線: %s", self.grpc_target)
                    retry_delay = 5  # 連線成功，重置退避

                    async for update in stream:
                        if update.config.channel_ids:
                            self._apply_config(update.config, update.update_reason)
                            logger.info(
                                "📡 收到設定更新 (v%d): channels=%s, by=%s, reason=%s",
                                update.config.version,
                                list(update.config.channel_ids),
                                update.updated_by,
                                update.update_reason,
                            )

            except grpc.aio.AioRpcError as e:
                if e.code() == grpc.StatusCode.UNAUTHENTICATED:
                    logger.error("gRPC 認證失敗: %s — 請檢查 MONITOR_API_KEY", e.details())
                    await asyncio.sleep(60)  # 認證錯誤不頻繁重試
                    continue
                logger.warning(
                    "gRPC stream 中斷: %s, %ds 後重連...", e.code(), retry_delay
                )
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.warning(
                    "gRPC 非預期錯誤: %s, %ds 後重連...", e, retry_delay
                )

            if not self._should_stop:
                await asyncio.sleep(retry_delay)
                retry_delay = min(retry_delay * 2, max_delay)

        logger.info("gRPC config watch loop 結束")

    def _apply_config(self, config, reason: str):
        """熱更新 SignalRouter 的過濾條件."""
        new_channel_ids = set(config.channel_ids)
        old_channel_ids = self.signal_router.channel_ids

        if new_channel_ids != old_channel_ids:
            added = new_channel_ids - old_channel_ids
            removed = old_channel_ids - new_channel_ids
            self.signal_router.channel_ids = new_channel_ids
            logger.info(
                "🔄 channel_ids 已更新: added=%s, removed=%s (reason=%s)",
                added or "無", removed or "無", reason,
            )

        # 同步更新其他過濾條件（如果有帶值）
        if config.guild_ids:
            new_guild_ids = set(config.guild_ids)
            if new_guild_ids != (self.signal_router.guild_ids or set()):
                self.signal_router.guild_ids = new_guild_ids
                logger.info("🔄 guild_ids 已更新: %s", new_guild_ids)

        if config.author_ids:
            new_author_ids = set(config.author_ids)
            if new_author_ids != (self.signal_router.author_ids or set()):
                self.signal_router.author_ids = new_author_ids
                logger.info("🔄 author_ids 已更新: %s", new_author_ids)

        if config.ignore_keywords:
            new_keywords = list(config.ignore_keywords)
            if new_keywords != self.signal_router.ignore_keywords:
                self.signal_router.ignore_keywords = new_keywords
                logger.info("🔄 ignore_keywords 已更新: %s", new_keywords)

        # per-source metadata（Phase 1: channel_id → source 元資料映射）
        if config.sources:
            source_map: dict[str, dict] = {}
            for src in config.sources:
                if src.channel_id:
                    source_map[src.channel_id] = {
                        "id": src.id,
                        "name": src.name,
                        "display_name": src.display_name,
                        "routing_mode": src.routing_mode,
                        "trade_mode": src.trade_mode,
                        "risk_multiplier": src.risk_multiplier,
                        "custom_prompt": src.custom_prompt,
                        # Phase: signals 表 audit chain 用 — Python 在 parse 時 snapshot
                        # 這個值，跟著 trade payload 送回 Java 寫進 signals.custom_prompt_version
                        "custom_prompt_version": src.custom_prompt_version,
                    }
            self.signal_router.source_metadata_map = source_map
            logger.info("🔄 source_metadata_map 已更新: %d 個來源", len(source_map))

        # AI prompt 熱更新（DB 管理的 prompt 版本，由 Admin 啟用後推送）
        if config.active_prompt and self.signal_router.ai_parser:
            current_ver = self.signal_router.ai_parser.prompt_version
            if config.active_prompt_version != current_ver:
                self.signal_router.ai_parser.update_system_prompt(
                    config.active_prompt, config.active_prompt_version
                )
                logger.info(
                    "🔄 AI prompt 已更新: v%d → v%d",
                    current_ver, config.active_prompt_version,
                )

        # AI model 熱更新（gRPC 中央推送；空字串=不覆蓋，沿用本地 config.yml 的 ai.model）
        # getattr 向下相容：舊版 proto stub 沒有 active_model 欄位時不報錯
        active_model = getattr(config, "active_model", "")
        if active_model and self.signal_router.ai_parser:
            if active_model != self.signal_router.ai_parser.model:
                self.signal_router.ai_parser.update_model(active_model)
