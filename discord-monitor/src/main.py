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
    dry_run = args.dry_run or config.api.dry_run

    # Setup logging
    setup_logging(config.logging.level, config.logging.file)

    if dry_run:
        logger.info("=== DRY RUN MODE — signals will be parsed but NOT executed ===")

    logger.info("Config loaded: monitoring channels %s", config.discord.channel_ids)

    # Initialize API client
    api_client = ApiClient(config.api)
    await api_client.start()

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
    router = SignalRouter(config.discord, api_client, dry_run=dry_run, ai_parser=ai_parser)
    cdp_client = CdpClient(config.cdp)

    # Heartbeat background task
    heartbeat_task: asyncio.Task | None = None

    async def heartbeat_loop(status_fn):
        """Send heartbeat to Spring Boot API every HEARTBEAT_INTERVAL seconds."""
        while True:
            try:
                await api_client.send_heartbeat(status_fn())
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
    if heartbeat_task and not heartbeat_task.done():
        heartbeat_task.cancel()
        try:
            await heartbeat_task
        except asyncio.CancelledError:
            pass

    await api_client.close()
    logger.info("Discord Monitor stopped.")


def run() -> None:
    """CLI entry point."""
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    run()
