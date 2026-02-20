"""HTTP client for calling the Spring Boot trading API."""
from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass

import aiohttp

from .config import ApiConfig

logger = logging.getLogger(__name__)

# Retry 配置
MAX_RETRIES = 3
RETRY_DELAYS = [1, 3, 10]  # 秒，指數退避


@dataclass
class ExecutionResult:
    """Result of a signal execution API call."""
    success: bool
    status_code: int
    summary: str
    error: str | None = None
    raw_response: dict | str | None = None


class ApiClient:
    """Async HTTP client for the Spring Boot trading API."""

    def __init__(self, config: ApiConfig):
        self.config = config
        self._session: aiohttp.ClientSession | None = None

    async def start(self) -> None:
        timeout = aiohttp.ClientTimeout(total=self.config.timeout)
        # 如果有設定 API Key，自動帶在所有請求的 header
        headers = {}
        if self.config.api_key:
            headers["X-Api-Key"] = self.config.api_key
            logger.info("Monitor API Key 已載入，將自動帶入請求 header")
        self._session = aiohttp.ClientSession(timeout=timeout, headers=headers)

    async def close(self) -> None:
        if self._session:
            await self._session.close()
            self._session = None

    async def _post_with_retry(self, url: str, payload: dict) -> ExecutionResult:
        """POST with retry logic: 3 attempts with exponential backoff.

        Only retries on 5xx server errors and network failures.
        4xx client errors are returned immediately (no retry).
        """
        last_error = None
        for attempt in range(MAX_RETRIES):
            try:
                async with self._session.post(url, json=payload) as resp:
                    try:
                        body = await resp.json()
                    except Exception:
                        body = await resp.text()

                    if resp.status == 200:
                        return ExecutionResult(
                            success=True,
                            status_code=200,
                            summary=str(body)[:300],
                            raw_response=body,
                        )

                    # 4xx client errors — don't retry
                    if resp.status < 500:
                        error_msg = body.get("error", str(body)) if isinstance(body, dict) else str(body)
                        return ExecutionResult(
                            success=False,
                            status_code=resp.status,
                            summary="",
                            error=error_msg,
                            raw_response=body,
                        )

                    # 5xx server errors — will retry
                    last_error = f"HTTP {resp.status}: {str(body)[:200]}"

            except Exception as e:
                last_error = f"Request failed: {e}"

            # Retry with backoff (except on last attempt)
            if attempt < MAX_RETRIES - 1:
                delay = RETRY_DELAYS[attempt]
                logger.warning(
                    "API retry %d/%d after %ds: %s → %s",
                    attempt + 1, MAX_RETRIES, delay, url, last_error,
                )
                await asyncio.sleep(delay)

        logger.error("All %d retries failed for %s: %s", MAX_RETRIES, url, last_error)
        return ExecutionResult(
            success=False,
            status_code=0,
            summary="",
            error=f"All {MAX_RETRIES} retries failed: {last_error}",
        )

    async def send_signal(
        self, message: str, dry_run: bool = False, source: dict | None = None,
    ) -> ExecutionResult:
        """POST {"message": "..."} to the Spring Boot API.

        Args:
            message: Raw signal text from Discord.
            dry_run: If True, POST to parse endpoint (no trading).
            source: Optional signal source metadata (platform, channel_id, etc.)
        """
        if dry_run:
            url = f"{self.config.base_url}{self.config.parse_endpoint}"
        else:
            url = f"{self.config.base_url}{self.config.execute_endpoint}"

        payload: dict = {"message": message}
        if source:
            payload["source"] = source
        return await self._post_with_retry(url, payload)

    async def send_trade(
        self, trade_request: dict, dry_run: bool = False, source: dict | None = None,
    ) -> ExecutionResult:
        """POST structured JSON to trading API.

        Endpoint depends on multi_user_enabled config:
        - False (default): /api/execute-trade (single account)
        - True: /api/broadcast-trade (broadcast to all users)

        Args:
            trade_request: Dict matching TradeRequest schema (action, symbol, side, etc.)
            dry_run: If True, log only without executing.
            source: Optional signal source metadata (platform, channel_id, etc.)
        """
        if dry_run:
            logger.info("[DRY RUN] Would send trade: %s", trade_request)
            return ExecutionResult(
                success=True,
                status_code=200,
                summary=f"[DRY RUN] {trade_request}",
            )

        if self.config.multi_user_enabled:
            endpoint = "/api/broadcast-trade"
        else:
            endpoint = "/api/execute-trade"

        url = f"{self.config.base_url}{endpoint}"
        payload = dict(trade_request)
        if source:
            payload["source"] = source
        logger.info("send_trade → %s %s %s", endpoint, trade_request.get("action"), trade_request.get("symbol"))
        return await self._post_with_retry(url, payload)

    async def check_health(self) -> bool:
        """Check if the Spring Boot API is reachable."""
        try:
            url = f"{self.config.base_url}/api/balance"
            async with self._session.get(url, timeout=aiohttp.ClientTimeout(total=5)) as resp:
                return resp.status == 200
        except Exception:
            return False

    async def send_heartbeat(self, status: str = "connected", ai_status: str = "active") -> bool:
        """Send heartbeat to Spring Boot API.

        Args:
            status: Current monitor status (connected / reconnecting).
            ai_status: AI parser status (active / disabled).

        Returns:
            True if heartbeat was acknowledged, False otherwise.
        """
        try:
            url = f"{self.config.base_url}/api/heartbeat"
            payload = {"status": status, "aiStatus": ai_status}
            async with self._session.post(
                url, json=payload, timeout=aiohttp.ClientTimeout(total=5)
            ) as resp:
                if resp.status == 200:
                    logger.debug("Heartbeat sent OK: status=%s", status)
                    return True
                logger.warning("Heartbeat response: HTTP %d", resp.status)
                return False
        except Exception as e:
            logger.warning("Heartbeat failed: %s", e)
            return False
