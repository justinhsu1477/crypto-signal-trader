"""SignalRouter 圖片訊息觀測 + image-first 分支測試。"""
from __future__ import annotations

import asyncio
import logging
from unittest.mock import AsyncMock, MagicMock

import pytest

from src.config import DiscordConfig, ImageSignalConfig
from src.signal_router import SignalRouter


def _make_msg(
    content: str = "",
    attachments: list[dict] | None = None,
    embed_images: list[dict] | None = None,
    embeds: list[dict] | None = None,
    channel_id: str = "ch_chen_ge",
    message_id: str = "msg001",
) -> dict:
    """建構一條包含 attachments / embed_images 的 Discord 訊息 dict。"""
    return {
        "id": message_id,
        "channel_id": channel_id,
        "guild_id": "guild1",
        "author_id": "author_chen",
        "author_name": "陈哥合约频道",
        "content": content,
        "embeds": embeds or [],
        "attachments": attachments or [],
        "embed_images": embed_images or [],
        "timestamp": "2026-05-08T10:00:00",
        "has_reference": False,
        "has_snapshots": False,
    }


def _make_router(
    image_enabled: bool = False,
    image_dry_run: bool = True,
    ai_parser=None,
) -> SignalRouter:
    """建構帶 image_signal 設定的 SignalRouter。"""
    discord_config = DiscordConfig(channel_ids=["ch_chen_ge"])
    image_config = ImageSignalConfig(
        enabled=image_enabled,
        dry_run=image_dry_run,
        allowed_symbols=["BTCUSDT"],
    )
    api_client = MagicMock()
    api_client.send_trade = AsyncMock()
    return SignalRouter(
        discord_config=discord_config,
        api_client=api_client,
        ai_parser=ai_parser,
        image_signal_config=image_config,
    )


class TestImageObservability:
    """觀測層測試：純圖訊息要被記錄成 WARNING，方便 grep 統計。"""

    @pytest.mark.asyncio
    async def test_image_only_message_logged_when_feature_off(self, caplog):
        """純圖訊息（content 空 + 有 attachment）即使功能關閉也要 log WARNING。"""
        router = _make_router(image_enabled=False)
        msg = _make_msg(
            content="",
            attachments=[{
                "id": "a1", "filename": "signal.png",
                "url": "https://cdn.discordapp.com/attachments/1/2/signal.png",
                "content_type": "image/png", "size": 200000,
                "width": 800, "height": 1000,
            }],
        )

        with caplog.at_level(logging.WARNING):
            await router.handle_message(msg)

        assert any("MISSED_IMAGE_ONLY" in rec.message for rec in caplog.records), \
            "純圖訊息應該被 log 成 MISSED_IMAGE_ONLY"
