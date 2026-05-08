"""Image utilities — 下載 Discord CDN 圖片 + 計算 SHA-256 + MIME 偵測。

設計原則：
- 純無狀態工具函式，不持久化任何東西
- 嚴格大小限制，避免 LLM 收到超大圖片爆 token
- MIME 從 bytes 真正偵測（不信任 server 回的 Content-Type）
"""
from __future__ import annotations

import hashlib
import logging
from typing import Optional

import aiohttp

logger = logging.getLogger(__name__)


class ImageFetchError(Exception):
    """圖片下載失敗（HTTP error、超大、網路錯誤等）。"""


# Magic numbers 對應 MIME — 只支援 vision LLM 能吃的格式
_MAGIC_NUMBERS = [
    (b"\x89PNG\r\n\x1a\n", "image/png"),
    (b"\xff\xd8\xff", "image/jpeg"),
    (b"GIF87a", "image/gif"),
    (b"GIF89a", "image/gif"),
]


def detect_mime_from_bytes(data: bytes) -> str:
    """從 magic number 偵測圖片 MIME。

    比信任 Content-Type header 更可靠（陳哥的圖可能透過 CDN 改 header）。
    """
    # WebP 特殊處理：RIFF...WEBP 才算 WebP，純 RIFF 可能是 WAV/AVI
    if data.startswith(b"RIFF") and len(data) >= 12 and data[8:12] == b"WEBP":
        return "image/webp"

    for magic, mime in _MAGIC_NUMBERS:
        if data.startswith(magic):
            return mime
    return "application/octet-stream"


def compute_sha256(data: bytes) -> str:
    """計算 SHA-256 hex digest（用於 dedup + audit）。"""
    return hashlib.sha256(data).hexdigest()


async def fetch_image(
    session: aiohttp.ClientSession,
    url: str,
    max_bytes: int,
    timeout_seconds: float = 10.0,
) -> tuple[bytes, str, str]:
    """下載 URL 並回傳 (bytes, mime, sha256)。

    Args:
        session: aiohttp ClientSession (重用既有 session 省 TLS handshake)
        url: 圖片 URL（Discord CDN 或 proxy_url 都行）
        max_bytes: 大小上限，超過 raise ImageFetchError
        timeout_seconds: HTTP timeout

    Raises:
        ImageFetchError: HTTP 非 200、超大、網路錯誤等
    """
    timeout = aiohttp.ClientTimeout(total=timeout_seconds)
    try:
        async with session.get(url, timeout=timeout) as resp:
            if resp.status != 200:
                raise ImageFetchError(f"HTTP {resp.status} from {url}")

            # 先看 Content-Length 快速拒絕（如果 server 有給）
            content_length = resp.headers.get("Content-Length")
            if content_length and int(content_length) > max_bytes:
                raise ImageFetchError(
                    f"Image Content-Length {content_length} exceeds max {max_bytes}"
                )

            # 邊收邊計大小，超過立即中斷（防 streaming attack）
            chunks = []
            total = 0
            async for chunk in resp.content.iter_chunked(64 * 1024):
                total += len(chunk)
                if total > max_bytes:
                    raise ImageFetchError(
                        f"Image streaming size exceeds max {max_bytes}"
                    )
                chunks.append(chunk)
            data = b"".join(chunks)

            mime = detect_mime_from_bytes(data)
            sha = compute_sha256(data)
            return data, mime, sha

    except aiohttp.ClientError as e:
        raise ImageFetchError(f"Network error fetching {url}: {e}") from e
    except ImageFetchError:
        raise
    except Exception as e:
        raise ImageFetchError(f"Unexpected error fetching {url}: {e}") from e
