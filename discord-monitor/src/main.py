"""Discord Signal Monitor — Entry point.

Connects to Discord via CDP, intercepts trading signals,
and forwards them to the Spring Boot API for execution.
"""
from __future__ import annotations

import argparse
import asyncio
import logging
import sys

from .api_client import ApiClient
from .cdp_client import CdpClient
from .config import load_config
from .signal_queue import SignalQueue
from .signal_router import SignalRouter

logger = logging.getLogger("discord_monitor")

# 心跳間隔（秒）
HEARTBEAT_INTERVAL = 30


def setup_logging(level: str, log_file: str | None = None) -> None:
    """Configure logging to console and optionally to file."""
    log_level = getattr(logging, level.upper(), logging.INFO)

    handlers: list[logging.Handler] = [logging.StreamHandler(sys.stdout)]
    if log_file:
        handlers.append(logging.FileHandler(log_file, encoding="utf-8"))

    logging.basicConfig(
        level=log_level,
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        datefmt="%Y-%m-%d %H:%M:%S",
        handlers=handlers,
    )


async def main() -> None:
    parser = argparse.ArgumentParser(description="Discord Signal Monitor")
    parser.add_argument("--config", default="config.yml", help="Path to config file")
    parser.add_argument("--dry-run", action="store_true", help="Parse only, no trading")
    args = parser.parse_args()

    # Load config
    config = load_config(args.config)
    config.validate()
    dry_run = args.dry_run or config.api.dry_run

    # Setup logging
    setup_logging(config.logging.level, config.logging.file)

    if dry_run:
        logger.info("=== DRY RUN MODE — signals will be parsed but NOT executed ===")

    logger.info("Config loaded: monitoring channels %s", config.discord.channel_ids)

    # Initialize API client
    api_client = ApiClient(config.api)
    await api_client.start()

    # Initialize signal queue (本地持久化，API 當機時暫存訊號)
    signal_queue = None
    if config.queue.enabled:
        signal_queue = SignalQueue(config.queue)
        queued_count = signal_queue.size()
        if queued_count > 0:
            logger.info("📥 訊號佇列已載入: %d 筆待重播", queued_count)
        else:
            logger.info("Signal queue enabled (dir: %s)", config.queue.queue_dir)

    # Health check
    healthy = await api_client.check_health()
    if not healthy:
        logger.warning(
            "Spring Boot API not reachable at %s. "
            "Signals will still be captured but API calls will fail. "
            "Start the API with: ./gradlew bootRun",
            config.api.base_url,
        )

    # AI parser (optional)
    ai_parser = None
    if config.ai.enabled:
        from .ai_parser import AiSignalParser
        ai_parser = AiSignalParser(config.ai)
        logger.info("AI signal parser enabled (model: %s)", config.ai.model)
    else:
        logger.info("AI parser disabled — using regex-only mode")

    # Build components
    router = SignalRouter(config.discord, api_client, dry_run=dry_run, ai_parser=ai_parser, signal_queue=signal_queue)
    cdp_client = CdpClient(config.cdp)

    # gRPC config watch (可選：從 Admin Dashboard 即時接收頻道設定變更)
    grpc_client = None
    if config.grpc.enabled and config.grpc.target:
        from .grpc_config_client import GrpcConfigClient
        grpc_client = GrpcConfigClient(
            grpc_target=config.grpc.target,
            api_key=config.api.api_key,
            signal_router=router,
            use_tls=config.grpc.use_tls,
        )
        initial_ok = await grpc_client.get_initial_config()
        if initial_ok:
            logger.info("gRPC 初始設定同步完成, channels: %s", router.channel_ids)
        else:
            logger.info("gRPC 初始同步跳過，使用 config.yml 的設定")
        grpc_client.start()
    else:
        logger.info("gRPC config watch 未啟用 — 使用靜態 config.yml 設定")

    # Heartbeat background task
    heartbeat_task: asyncio.Task | None = None

    # AI parser 狀態：有初始化成功就是 active，否則 disabled
    ai_active = ai_parser is not None and ai_parser.client is not None

    async def heartbeat_loop(status_fn):
        """Send heartbeat to Spring Boot API every HEARTBEAT_INTERVAL seconds.

        同時負責 queue replay：heartbeat 成功 + queue 有資料 → 自動重播。
        """
        while True:
            try:
                token_stats = ai_parser.get_token_stats() if ai_parser else None
                heartbeat_ok = await api_client.send_heartbeat(
                    status_fn(),
                    ai_status="active" if ai_active else "disabled",
                    ai_token_stats=token_stats,
                )

                # Queue replay: API 恢復時自動重播暫存的訊號
                if heartbeat_ok and signal_queue and signal_queue.size() > 0:
                    logger.info(
                        "🔄 API 已恢復，開始重播 %d 筆佇列訊號...",
                        signal_queue.size(),
                    )
                    await _replay_queue(api_client, signal_queue)

            except Exception as e:
                logger.debug("Heartbeat error (non-fatal): %s", e)
            await asyncio.sleep(HEARTBEAT_INTERVAL)

    # Track current connection status for heartbeat
    connection_status = "starting"

    def get_status() -> str:
        return connection_status

    # Main loop with reconnection
    attempt = 0
    while True:
        try:
            logger.info("Connecting to Discord CDP at %s:%d...", config.cdp.host, config.cdp.port)
            connection_status = "connecting"
            await cdp_client.connect()
            logger.info("Connected! Listening for trading signals...")
            attempt = 0
            connection_status = "connected"

            # Start heartbeat if not running
            if heartbeat_task is None or heartbeat_task.done():
                heartbeat_task = asyncio.create_task(heartbeat_loop(get_status))

            await cdp_client.listen(router.handle_message)
        except KeyboardInterrupt:
            logger.info("Shutting down...")
            break
        except Exception as e:
            attempt += 1
            connection_status = "reconnecting"
            max_attempts = config.cdp.max_reconnect_attempts
            if max_attempts and attempt > max_attempts:
                logger.error("Max reconnect attempts (%d) reached. Exiting.", max_attempts)
                break

            wait = min(config.cdp.reconnect_interval * attempt, 60)
            logger.warning(
                "CDP connection lost: %s. Reconnecting in %ds (attempt %d)...",
                e, wait, attempt,
            )
            await asyncio.sleep(wait)
        finally:
            await cdp_client.disconnect()

    # Cleanup
    if grpc_client:
        await grpc_client.stop()

    if heartbeat_task and not heartbeat_task.done():
        heartbeat_task.cancel()
        try:
            await heartbeat_task
        except asyncio.CancelledError:
            pass

    await api_client.close()
    logger.info("Discord Monitor stopped.")


async def _replay_queue(api_client: ApiClient, queue: SignalQueue) -> None:
    """依序重播佇列中的訊號。遇到 5xx/網路錯誤立即停止（API 可能又掛了）。

    重播策略：
    - FIFO 順序（最舊的先）
    - 200 成功 → dequeue 移除
    - 4xx client 錯誤 → 移除（payload 有問題，重播也會失敗）
    - 5xx / 網路錯 → increment_attempt + 停止（等下一輪 heartbeat）
    - 超過 max_replay_attempts → 移除 + 警告 log

    去重保護鏈：
    - Server 5分鐘 hash 去重 → 返回 200 SKIPPED → dequeue ✅
    - Server message_id 永久去重 → 返回 200 SKIPPED → dequeue ✅
    - 不會造成重複下單
    """
    entries = queue.peek_all()
    success_count = 0
    fail_count = 0

    for entry in entries:
        call_type = entry.get("call_type")
        payload = entry.get("payload", {})
        source = entry.get("source")
        dry_run = entry.get("dry_run", False)
        entry_id = entry.get("id", "unknown")
        attempt_count = entry.get("attempt_count", 0)

        # 超過最大重播次數 → 移除
        if attempt_count >= queue.config.max_replay_attempts:
            logger.warning(
                "⚠️ 佇列訊號 %s 已重播 %d 次仍失敗，移除: %s %s",
                entry_id, attempt_count,
                payload.get("action", ""),
                payload.get("symbol", ""),
            )
            queue.dequeue(entry_id)
            continue

        logger.info(
            "▶️ 重播佇列訊號 %s: %s %s %s (attempt %d)",
            entry_id, call_type,
            payload.get("action", ""),
            payload.get("symbol", payload.get("message", "")[:60]),
            attempt_count + 1,
        )

        if call_type == "send_trade":
            result = await api_client.send_trade(payload, dry_run=dry_run, source=source)
        elif call_type == "send_signal":
            result = await api_client.send_signal(
                payload.get("message", ""), dry_run=dry_run, source=source,
            )
        else:
            logger.warning("未知 call_type %s，移除佇列訊號 %s", call_type, entry_id)
            queue.dequeue(entry_id)
            continue

        if result.success:
            logger.info("✅ 重播成功 %s: %s", entry_id, result.summary[:200])
            queue.dequeue(entry_id)
            success_count += 1
        elif 400 <= result.status_code < 500:
            # 4xx = client 錯誤（payload 問題、去重攔截等），不值得重試
            logger.warning(
                "🚫 重播被拒 %s (HTTP %d): %s — 已移除",
                entry_id, result.status_code, result.error,
            )
            queue.dequeue(entry_id)
        else:
            # 5xx / 網路錯 → API 可能又掛了，停止重播等下一輪
            fail_count += 1
            logger.warning(
                "❌ 重播失敗 %s (HTTP %d): %s — 停止重播，等下一輪 heartbeat",
                entry_id, result.status_code, result.error,
            )
            queue.increment_attempt(entry_id)
            break  # 不繼續重播，等下一輪 heartbeat

    if success_count > 0 or fail_count > 0:
        logger.info(
            "📊 佇列重播結果: 成功=%d, 失敗=%d, 剩餘=%d",
            success_count, fail_count, queue.size(),
        )


def run() -> None:
    """CLI entry point."""
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    run()
