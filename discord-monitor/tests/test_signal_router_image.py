"""SignalRouter 圖片訊息觀測 + image-first 分支測試。"""
from __future__ import annotations

import asyncio
import logging
from unittest.mock import AsyncMock, MagicMock, patch

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


class TestImageFirstRouting:
    """image-first 分支測試：有圖優先走 vision，文字訊號不受影響。"""

    def setup_method(self):
        # 通用 mock Gemini parser
        self.ai_parser = MagicMock()
        self.ai_parser.parse = AsyncMock(return_value=None)
        self.ai_parser.parse_with_image = AsyncMock(return_value={
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "SHORT",
            "entry_price": 82800,
            "stop_loss": 84500,
            "take_profit": 80800,
        })

    @pytest.mark.asyncio
    async def test_pure_text_message_uses_text_path(self):
        """純文字訊息 → 走原本 ai_parser.parse()，不碰 parse_with_image。"""
        self.ai_parser.parse = AsyncMock(return_value={
            "action": "ENTRY", "symbol": "BTCUSDT", "side": "SHORT",
            "entry_price": 82800, "stop_loss": 84500,
        })
        router = _make_router(image_enabled=True, image_dry_run=True, ai_parser=self.ai_parser)
        msg = _make_msg(content="BTC 82800 做空 SL 84500", attachments=[], embed_images=[])

        await router.handle_message(msg)

        self.ai_parser.parse.assert_called_once()
        self.ai_parser.parse_with_image.assert_not_called()

    @pytest.mark.asyncio
    async def test_pure_image_uses_image_path(self):
        """純圖訊息 → 走 image path（功能開啟時）。"""
        router = _make_router(image_enabled=True, image_dry_run=True, ai_parser=self.ai_parser)
        msg = _make_msg(
            content="",
            attachments=[{
                "id": "a1", "filename": "signal.png",
                "url": "https://cdn.discordapp.com/x.png",
                "content_type": "image/png", "size": 200000,
            }],
        )

        # 模擬 image fetch — 不真的下載
        with patch("src.signal_router.fetch_image", new=AsyncMock(
            return_value=(b"\x89PNG\r\n\x1a\n", "image/png", "abc123"),
        )):
            await router.handle_message(msg)

        self.ai_parser.parse_with_image.assert_called_once()
        self.ai_parser.parse.assert_not_called()

    @pytest.mark.asyncio
    async def test_image_with_text_uses_image_path(self):
        """圖+文字混合 → 走 image path（圖優先，文字當補充）。"""
        router = _make_router(image_enabled=True, image_dry_run=True, ai_parser=self.ai_parser)
        msg = _make_msg(
            content="BTC市价82600-83000附近做空",
            attachments=[{
                "id": "a1", "filename": "signal.png",
                "url": "https://cdn.discordapp.com/x.png",
                "content_type": "image/png", "size": 200000,
            }],
        )

        with patch("src.signal_router.fetch_image", new=AsyncMock(
            return_value=(b"\x89PNG\r\n\x1a\n", "image/png", "abc123"),
        )):
            await router.handle_message(msg)

        self.ai_parser.parse_with_image.assert_called_once()
        # 文字被當作 text_content 傳進去
        call_kwargs = self.ai_parser.parse_with_image.call_args.kwargs
        assert "BTC市价82600-83000" in call_kwargs.get("text_content", "")

    @pytest.mark.asyncio
    async def test_image_path_passes_source_custom_prompt(self):
        """image path 也要帶 per-source custom_prompt，避免圖訊號吃不到來源方言。"""
        router = _make_router(image_enabled=True, image_dry_run=True, ai_parser=self.ai_parser)
        router.source_metadata_map["ch_chen_ge"] = {
            "name": "chenge",
            "display_name": "陳哥",
            "trade_mode": "AUTO",
            "risk_multiplier": 1.0,
            "custom_prompt": "圖片中的藍色框為 entry，紅色框為 stop loss。",
        }
        msg = _make_msg(
            content="看圖",
            attachments=[{
                "id": "a1", "filename": "signal.png",
                "url": "https://cdn.discordapp.com/x.png",
                "content_type": "image/png", "size": 200000,
            }],
        )

        with patch("src.signal_router.fetch_image", new=AsyncMock(
            return_value=(b"\x89PNG\r\n\x1a\n", "image/png", "abc123"),
        )):
            await router.handle_message(msg)

        call_kwargs = self.ai_parser.parse_with_image.call_args.kwargs
        assert call_kwargs["source_prompt"] == "圖片中的藍色框為 entry，紅色框為 stop loss。"
        assert call_kwargs["source_name"] == "chenge"

    @pytest.mark.asyncio
    async def test_image_path_disabled_falls_back_to_text(self):
        """image_signal.enabled=false → 即使有圖也走原文字流（=現況）。"""
        self.ai_parser.parse = AsyncMock(return_value={
            "action": "ENTRY", "symbol": "BTCUSDT", "side": "SHORT",
            "entry_price": 82800, "stop_loss": 84500,
        })
        router = _make_router(image_enabled=False, ai_parser=self.ai_parser)
        msg = _make_msg(
            content="BTC做空",
            attachments=[{
                "id": "a1", "filename": "signal.png",
                "url": "https://cdn.discordapp.com/x.png",
                "content_type": "image/png", "size": 200000,
            }],
        )

        await router.handle_message(msg)

        # 功能關閉時 image_path 完全不啟用
        self.ai_parser.parse_with_image.assert_not_called()

    @pytest.mark.asyncio
    async def test_non_btc_image_signal_dropped(self):
        """陳哥發 ETH 訊號圖 → BTC 白名單擋掉，不送下游。"""
        self.ai_parser.parse_with_image = AsyncMock(return_value={
            "action": "ENTRY", "symbol": "ETHUSDT", "side": "LONG",
            "entry_price": 2500, "stop_loss": 2450,
        })
        router = _make_router(image_enabled=True, image_dry_run=False, ai_parser=self.ai_parser)
        msg = _make_msg(
            content="",
            attachments=[{
                "id": "a1", "filename": "eth.png",
                "url": "https://cdn.discordapp.com/x.png",
                "content_type": "image/png", "size": 100000,
            }],
        )

        with patch("src.signal_router.fetch_image", new=AsyncMock(
            return_value=(b"\x89PNG\r\n\x1a\n", "image/png", "abc"),
        )):
            await router.handle_message(msg)

        # parse_with_image 被叫了，但下游 send_trade 不該被叫
        router.api_client.send_trade.assert_not_called()

    @pytest.mark.asyncio
    async def test_dry_run_does_not_send_trade(self):
        """dry_run=true → 解析完成但不呼叫 send_trade。"""
        router = _make_router(image_enabled=True, image_dry_run=True, ai_parser=self.ai_parser)
        msg = _make_msg(
            content="",
            attachments=[{
                "id": "a1", "filename": "signal.png",
                "url": "https://cdn.discordapp.com/x.png",
                "content_type": "image/png", "size": 200000,
            }],
        )

        with patch("src.signal_router.fetch_image", new=AsyncMock(
            return_value=(b"\x89PNG\r\n\x1a\n", "image/png", "abc"),
        )):
            await router.handle_message(msg)

        # parse 跑了但沒送 Java
        self.ai_parser.parse_with_image.assert_called_once()
        router.api_client.send_trade.assert_not_called()

    @pytest.mark.asyncio
    async def test_image_path_send_trade_when_btc_and_not_dry_run(self):
        """BTC 訊號 + dry_run=false → 真的送 Java。"""
        router = _make_router(image_enabled=True, image_dry_run=False, ai_parser=self.ai_parser)
        msg = _make_msg(
            content="",
            attachments=[{
                "id": "a1", "filename": "btc.png",
                "url": "https://cdn.discordapp.com/x.png",
                "content_type": "image/png", "size": 200000,
            }],
        )

        with patch("src.signal_router.fetch_image", new=AsyncMock(
            return_value=(b"\x89PNG\r\n\x1a\n", "image/png", "sha_btc"),
        )):
            await router.handle_message(msg)

        router.api_client.send_trade.assert_called_once()
        # 確認 source 帶了 attachment metadata
        call_kwargs = router.api_client.send_trade.call_args.kwargs
        source = call_kwargs.get("source") or {}
        assert "attachment" in source
        assert source["attachment"]["sha256"] == "sha_btc"


class TestImageE2E:
    """端對端整合測試：模擬陳哥的圖+文字訊號從 CDP → image_path → send_trade。"""

    @pytest.mark.asyncio
    async def test_chen_ge_btc_short_signal_full_flow(self):
        """模擬本月實際截圖（陳哥的紫色 BTC SHORT 框）的完整解析路徑。"""
        # 1. Setup: enable image path, dry_run=false (要驗證真的送出去)
        ai_parser = MagicMock()
        ai_parser.parse_with_image = AsyncMock(return_value={
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "SHORT",
            "entry_price": 82800,
            "stop_loss": 84500,
            "take_profit": 80800,
        })
        ai_parser.prompt_version = 7
        router = _make_router(image_enabled=True, image_dry_run=False, ai_parser=ai_parser)

        # 2. 模擬陳哥訊息：含文字 + 紫色框圖片附件
        msg = _make_msg(
            content="BTC市价82600-83000附近正常仓位入场做空。",
            attachments=[{
                "id": "att_001",
                "filename": "陳哥訊號.png",
                "url": "https://cdn.discordapp.com/attachments/123/456/signal.png",
                "content_type": "image/png",
                "size": 350000,
                "width": 800, "height": 1000,
            }],
            message_id="msg_chen_001",
        )

        # 3. 跑流程
        with patch("src.signal_router.fetch_image", new=AsyncMock(
            return_value=(b"\x89PNG\r\n\x1a\nFAKE_PURPLE_BANNER", "image/png", "sha_e2e_001"),
        )):
            await router.handle_message(msg)

        # 4. 驗證：parse_with_image 收到正確的 text + image
        ai_parser.parse_with_image.assert_called_once()
        call = ai_parser.parse_with_image.call_args
        assert "82600-83000" in call.kwargs["text_content"]
        assert call.kwargs["mime_type"] == "image/png"

        # 5. 驗證：send_trade 收到完整 payload
        router.api_client.send_trade.assert_called_once()
        send_call = router.api_client.send_trade.call_args
        trade_request = send_call.kwargs["trade_request"]
        assert trade_request["action"] == "ENTRY"
        assert trade_request["symbol"] == "BTCUSDT"
        assert trade_request["side"] == "SHORT"
        assert trade_request["stop_loss"] == 84500
        assert trade_request["prompt_version"] == 7

        source = send_call.kwargs["source"]
        assert source["message_id"] == "msg_chen_001"
        assert source["channel_id"] == "ch_chen_ge"
        assert source["attachment"]["sha256"] == "sha_e2e_001"
        assert source["attachment"]["filename"] == "陳哥訊號.png"

    @pytest.mark.asyncio
    async def test_text_signal_unaffected_by_image_feature(self):
        """純文字訊號（陳哥也會發）— 確認 image feature 開啟不影響文字流。"""
        ai_parser = MagicMock()
        ai_parser.parse = AsyncMock(return_value={
            "action": "ENTRY", "symbol": "BTCUSDT", "side": "LONG",
            "entry_price": 95000, "stop_loss": 93000, "take_profit": 98000,
        })
        ai_parser.parse_with_image = AsyncMock(return_value=None)
        ai_parser.prompt_version = 7
        router = _make_router(image_enabled=True, image_dry_run=False, ai_parser=ai_parser)

        # 純文字訊號（無圖）
        msg = _make_msg(
            content="📢 BTC 做多 入場 95000 SL 93000 TP 98000",
            attachments=[],
            embed_images=[],
            message_id="msg_text_001",
        )

        await router.handle_message(msg)

        # parse() 被叫了，parse_with_image() 沒被叫
        ai_parser.parse.assert_called_once()
        ai_parser.parse_with_image.assert_not_called()
        # 訊息照樣送 Java
        router.api_client.send_trade.assert_called_once()


class TestImageEdgeCases:
    """補齊 image-first 分支的 edge cases — code review 補強。"""

    def setup_method(self):
        self.ai_parser = MagicMock()
        self.ai_parser.parse = AsyncMock(return_value=None)
        # 預設 parse_with_image 回完整 BTC SHORT
        self.ai_parser.parse_with_image = AsyncMock(return_value={
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "SHORT",
            "entry_price": 82800,
            "stop_loss": 84500,
        })
        self.ai_parser.prompt_version = 0

    @pytest.mark.asyncio
    async def test_parse_with_image_returns_none_skips_send(self):
        """parse_with_image 回 None（解析失敗）→ 不送 Java + log 警告。"""
        self.ai_parser.parse_with_image = AsyncMock(return_value=None)
        router = _make_router(image_enabled=True, image_dry_run=False, ai_parser=self.ai_parser)
        msg = _make_msg(
            content="",
            attachments=[{
                "id": "a1", "filename": "x.png",
                "url": "https://cdn.discordapp.com/x.png",
                "content_type": "image/png", "size": 100000,
            }],
        )

        with patch("src.signal_router.fetch_image", new=AsyncMock(
            return_value=(b"\x89PNG\r\n\x1a\n", "image/png", "abc"),
        )):
            await router.handle_message(msg)

        self.ai_parser.parse_with_image.assert_called_once()
        router.api_client.send_trade.assert_not_called()

    @pytest.mark.asyncio
    async def test_info_action_not_forwarded(self):
        """parse_with_image 回 INFO action（純技術分析圖、回顧）→ 不送 Java。"""
        self.ai_parser.parse_with_image = AsyncMock(return_value={
            "action": "INFO",
            "symbol": "BTCUSDT",
        })
        router = _make_router(image_enabled=True, image_dry_run=False, ai_parser=self.ai_parser)
        msg = _make_msg(
            content="",
            attachments=[{
                "id": "a1", "filename": "kline.png",
                "url": "https://cdn.discordapp.com/k.png",
                "content_type": "image/png", "size": 100000,
            }],
        )

        with patch("src.signal_router.fetch_image", new=AsyncMock(
            return_value=(b"\x89PNG\r\n\x1a\n", "image/png", "abc"),
        )):
            await router.handle_message(msg)

        router.api_client.send_trade.assert_not_called()

    @pytest.mark.asyncio
    async def test_embed_images_only_no_attachments(self):
        """訊息只有 embed_images（無 attachments） → 仍能走 image path。"""
        router = _make_router(image_enabled=True, image_dry_run=False, ai_parser=self.ai_parser)
        msg = _make_msg(
            content="",
            attachments=[],
            embed_images=[{
                "url": "https://cdn.discordapp.com/embed.png",
                "width": 800, "height": 600,
            }],
        )

        with patch("src.signal_router.fetch_image", new=AsyncMock(
            return_value=(b"\x89PNG\r\n\x1a\n", "image/png", "sha_embed"),
        )):
            await router.handle_message(msg)

        # parse_with_image 被叫了，且圖片 URL 是 embed 的
        self.ai_parser.parse_with_image.assert_called_once()
        router.api_client.send_trade.assert_called_once()

    @pytest.mark.asyncio
    async def test_fetch_image_failure_handled_gracefully(self):
        """fetch_image 拋 ImageFetchError → 不影響後續訊息、不送 Java。"""
        from src.image_utils import ImageFetchError

        router = _make_router(image_enabled=True, image_dry_run=False, ai_parser=self.ai_parser)
        msg = _make_msg(
            content="",
            attachments=[{
                "id": "a1", "filename": "x.png",
                "url": "https://cdn.discordapp.com/broken.png",
                "content_type": "image/png", "size": 100000,
            }],
        )

        async def fake_fetch_fail(*args, **kwargs):
            raise ImageFetchError("HTTP 404 from URL")

        with patch("src.signal_router.fetch_image", new=fake_fetch_fail):
            await router.handle_message(msg)

        # 解析根本沒被呼叫，trade 也沒送
        self.ai_parser.parse_with_image.assert_not_called()
        router.api_client.send_trade.assert_not_called()

    @pytest.mark.asyncio
    async def test_multi_image_processes_first_only(self):
        """訊息含多張圖 → 只處理第一張（陳哥訊號通常一張）。"""
        router = _make_router(image_enabled=True, image_dry_run=False, ai_parser=self.ai_parser)
        msg = _make_msg(
            content="",
            attachments=[
                {
                    "id": "a1", "filename": "first.png",
                    "url": "https://cdn.discordapp.com/first.png",
                    "content_type": "image/png", "size": 100000,
                },
                {
                    "id": "a2", "filename": "second.png",
                    "url": "https://cdn.discordapp.com/second.png",
                    "content_type": "image/png", "size": 100000,
                },
            ],
        )

        fetch_calls = []
        async def tracking_fetch(session, url, max_bytes, timeout_seconds=10.0):
            fetch_calls.append(url)
            return (b"\x89PNG\r\n\x1a\n", "image/png", "sha_first")

        with patch("src.signal_router.fetch_image", new=tracking_fetch):
            await router.handle_message(msg)

        # 只下載第一張圖
        assert len(fetch_calls) == 1, f"Expected 1 fetch call, got {len(fetch_calls)}: {fetch_calls}"
        assert "first.png" in fetch_calls[0]
        # parse 被叫一次
        self.ai_parser.parse_with_image.assert_called_once()
