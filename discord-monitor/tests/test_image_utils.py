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


@pytest.mark.asyncio
async def test_fetch_image_success():
    """成功下載 → 回傳 (bytes, mime, sha256)。"""
    fake_bytes = b"\x89PNG\r\n\x1a\n" + b"FAKE" * 100
    expected_sha = hashlib.sha256(fake_bytes).hexdigest()

    mock_response = AsyncMock()
    mock_response.status = 200
    mock_response.read = AsyncMock(return_value=fake_bytes)
    mock_response.headers = {"Content-Type": "image/png"}

    mock_session = MagicMock()
    mock_session.get = MagicMock(return_value=AsyncMock(
        __aenter__=AsyncMock(return_value=mock_response),
        __aexit__=AsyncMock(return_value=False),
    ))

    data, mime, sha = await fetch_image(
        mock_session, "https://example.com/img.png", max_bytes=1024 * 1024,
    )
    assert data == fake_bytes
    assert mime == "image/png"
    assert sha == expected_sha


@pytest.mark.asyncio
async def test_fetch_image_size_limit_exceeded():
    """檔案超過 max_bytes 必須 raise ImageFetchError。"""
    big_bytes = b"X" * (2 * 1024 * 1024)
    mock_response = AsyncMock()
    mock_response.status = 200
    mock_response.read = AsyncMock(return_value=big_bytes)
    mock_response.headers = {"Content-Type": "image/png"}

    mock_session = MagicMock()
    mock_session.get = MagicMock(return_value=AsyncMock(
        __aenter__=AsyncMock(return_value=mock_response),
        __aexit__=AsyncMock(return_value=False),
    ))

    with pytest.raises(ImageFetchError, match="exceeds max"):
        await fetch_image(
            mock_session, "https://example.com/img.png", max_bytes=1024 * 1024,
        )


@pytest.mark.asyncio
async def test_fetch_image_http_error():
    """HTTP 非 200 回應 raise ImageFetchError。"""
    mock_response = AsyncMock()
    mock_response.status = 404
    mock_response.headers = {}

    mock_session = MagicMock()
    mock_session.get = MagicMock(return_value=AsyncMock(
        __aenter__=AsyncMock(return_value=mock_response),
        __aexit__=AsyncMock(return_value=False),
    ))

    with pytest.raises(ImageFetchError, match="HTTP 404"):
        await fetch_image(
            mock_session, "https://example.com/img.png", max_bytes=1024 * 1024,
        )
