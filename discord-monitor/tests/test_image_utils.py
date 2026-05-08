"""image_utils 測試：HTTP 下載、SHA-256、MIME 檢查。"""
from __future__ import annotations

import hashlib
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from src.image_utils import (
    ImageFetchError,
    compute_sha256,
    detect_mime_from_bytes,
    fetch_image,
)


def test_compute_sha256_known_input():
    """SHA-256 必須是穩定的 hash。"""
    data = b"hello world"
    expected = hashlib.sha256(data).hexdigest()
    assert compute_sha256(data) == expected


def test_detect_mime_png():
    """PNG magic number 必須被偵測。"""
    png_header = b"\x89PNG\r\n\x1a\n" + b"\x00" * 100
    assert detect_mime_from_bytes(png_header) == "image/png"


def test_detect_mime_jpeg():
    """JPEG magic number 必須被偵測。"""
    jpeg_header = b"\xff\xd8\xff\xe0" + b"\x00" * 100
    assert detect_mime_from_bytes(jpeg_header) == "image/jpeg"


def test_detect_mime_unknown():
    """未知格式回 application/octet-stream。"""
    garbage = b"\x00\x01\x02\x03"
    assert detect_mime_from_bytes(garbage) == "application/octet-stream"


class _FakeContent:
    """模擬 aiohttp resp.content 的 iter_chunked()。"""
    def __init__(self, data: bytes, chunk_size: int = 64 * 1024):
        self._data = data
        self._chunk_size = chunk_size

    def iter_chunked(self, n: int):
        async def _gen():
            for i in range(0, len(self._data), n):
                yield self._data[i:i + n]
        return _gen()


def _make_mock_session(data: bytes, status: int = 200, headers: dict | None = None):
    """建構模擬 streaming response 的 mock session。"""
    mock_response = MagicMock()
    mock_response.status = status
    mock_response.headers = headers or {}
    mock_response.content = _FakeContent(data)

    mock_session = MagicMock()
    mock_session.get = MagicMock(return_value=AsyncMock(
        __aenter__=AsyncMock(return_value=mock_response),
        __aexit__=AsyncMock(return_value=False),
    ))
    return mock_session


@pytest.mark.asyncio
async def test_fetch_image_success():
    """成功下載 → 回傳 (bytes, mime, sha256)。"""
    fake_bytes = b"\x89PNG\r\n\x1a\n" + b"FAKE" * 100
    expected_sha = hashlib.sha256(fake_bytes).hexdigest()
    mock_session = _make_mock_session(fake_bytes, headers={"Content-Type": "image/png"})

    data, mime, sha = await fetch_image(
        mock_session, "https://example.com/img.png", max_bytes=1024 * 1024,
    )
    assert data == fake_bytes
    assert mime == "image/png"
    assert sha == expected_sha


@pytest.mark.asyncio
async def test_fetch_image_size_limit_exceeded():
    """檔案超過 max_bytes 必須 raise ImageFetchError（streaming 偵測）。"""
    big_bytes = b"X" * (2 * 1024 * 1024)
    mock_session = _make_mock_session(big_bytes, headers={"Content-Type": "image/png"})

    with pytest.raises(ImageFetchError, match="exceeds max"):
        await fetch_image(
            mock_session, "https://example.com/img.png", max_bytes=1024 * 1024,
        )


@pytest.mark.asyncio
async def test_fetch_image_content_length_rejected_early():
    """Content-Length header 超過上限 → 不下載 body 直接 raise。"""
    mock_session = _make_mock_session(
        b"",
        headers={"Content-Length": str(5 * 1024 * 1024)},
    )

    with pytest.raises(ImageFetchError, match="Content-Length"):
        await fetch_image(
            mock_session, "https://example.com/img.png", max_bytes=1024 * 1024,
        )


@pytest.mark.asyncio
async def test_fetch_image_http_error():
    """HTTP 非 200 回應 raise ImageFetchError。"""
    mock_session = _make_mock_session(b"", status=404)

    with pytest.raises(ImageFetchError, match="HTTP 404"):
        await fetch_image(
            mock_session, "https://example.com/img.png", max_bytes=1024 * 1024,
        )


def test_detect_mime_webp_proper():
    """合法 WebP（RIFF...WEBP）正確識別為 image/webp。"""
    from src.image_utils import detect_mime_from_bytes
    webp_header = b"RIFF" + b"\x00\x00\x00\x00" + b"WEBP" + b"\x00" * 100
    assert detect_mime_from_bytes(webp_header) == "image/webp"


def test_detect_mime_riff_not_webp_is_unknown():
    """RIFF 但不是 WEBP（例如 WAV）→ 不該誤判成 image/webp。"""
    from src.image_utils import detect_mime_from_bytes
    wav_header = b"RIFF" + b"\x00\x00\x00\x00" + b"WAVE" + b"\x00" * 100
    assert detect_mime_from_bytes(wav_header) == "application/octet-stream"
