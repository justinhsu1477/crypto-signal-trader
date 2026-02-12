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
        self._session = aiohttp.ClientSession(timeout=timeout)

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

    async def send_signal(self, message: str, dry_run: bool = False) -> ExecutionResult:
        """POST {"message": "..."} to the Spring Boot API.

        Args:
            message: Raw signal text from Discord.
            dry_run: If True, POST to parse endpoint (no trading).
        """
        if dry_run:
            url = f"{self.config.base_url}{self.config.parse_endpoint}"
        else:
            url = f"{self.config.base_url}{self.config.execute_endpoint}"

        payload = {"message": message}
        return await self._post_with_retry(url, payload)

    async def send_trade(self, trade_request: dict, dry_run: bool = False) -> ExecutionResult:
        """POST structured JSON to /api/execute-trade.

        Args:
            trade_request: Dict matching TradeRequest schema (action, symbol, side, etc.)
            dry_run: If True, log only without executing.
        """
        if dry_run:
            logger.info("[DRY RUN] Would send trade: %s", trade_request)
            return ExecutionResult(
                success=True,
                status_code=200,
                summary=f"[DRY RUN] {trade_request}",
            )

        url = f"{self.config.base_url}/api/execute-trade"
        return await self._post_with_retry(url, trade_request)

    async def check_health(self) -> bool:
        """Check if the Spring Boot API is reachable."""
        try:
            url = f"{self.config.base_url}/api/balance"
            async with self._session.get(url, timeout=aiohttp.ClientTimeout(total=5)) as resp:
                return resp.status == 200
        except Exception:
            return False
