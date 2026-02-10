"""HTTP client for calling the Spring Boot trading API."""
from __future__ import annotations

import logging
from dataclasses import dataclass

import aiohttp

from .config import ApiConfig

logger = logging.getLogger(__name__)


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
                else:
                    error_msg = body.get("error", str(body)) if isinstance(body, dict) else str(body)
                    return ExecutionResult(
                        success=False,
                        status_code=resp.status,
                        summary="",
                        error=error_msg,
                        raw_response=body,
                    )
        except Exception as e:
            return ExecutionResult(
                success=False,
                status_code=0,
                summary="",
                error=f"HTTP request failed: {e}",
            )

    async def check_health(self) -> bool:
        """Check if the Spring Boot API is reachable."""
        try:
            url = f"{self.config.base_url}/api/balance"
            async with self._session.get(url, timeout=aiohttp.ClientTimeout(total=5)) as resp:
                return resp.status == 200
        except Exception:
            return False
