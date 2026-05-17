"""
單元測試 — SignalRouter 訊號源 → 個人 channel webhook mirror

行為合約：
1. 訊息來自有 mapping 的 channel → 觸發 fire-and-forget POST 到對應 webhook URL
2. 訊息來自沒 mapping 的 channel（但通過 channel filter）→ 不 POST
3. mirror_webhooks 為空 → 完全不 POST（功能停用）
4. POST payload 含原文 content / author / 圖片 embed URLs
5. webhook POST 失敗（4xx / 5xx / network error）→ 主流程不受影響繼續走完

設計重點：
- Mirror 是 fire-and-forget（asyncio.create_task），不能 block AI parse / send_trade
- 不重試 — 不要為了 mirror 漏訊號
- 不影響既有 channel_ids whitelist 過濾（必須先通過才會 mirror）
"""
from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from src.config import DiscordConfig
from src.signal_router import SignalRouter


def _make_msg(
    content: str = "BTC long 60000",
    embeds: list[dict] | None = None,
    channel_id: str = "ch_chen_ge",
    message_id: str = "msg001",
    author_name: str = "陳哥",
) -> dict:
    """建構一條 Discord 訊息 dict（與 CDP hook 輸出格式對齊）。"""
    return {
        "id": message_id,
        "channel_id": channel_id,
        "guild_id": "guild1",
        "author_id": "author1",
        "author_name": author_name,
        "content": content,
        "embeds": embeds or [],
        "timestamp": "2026-05-17T01:00:00",
    }


class TestMirrorWebhook:
    """SignalRouter mirror webhook 行為測試。"""

    def setup_method(self):
        self.api_client = MagicMock()
        self.api_client.send_signal = AsyncMock()
        self.api_client.send_trade = AsyncMock()
        # ai_parser 預設 None → SignalRouter 走 fallback path，不影響 mirror 邏輯測試
        self.ai_parser = None

    def _make_router(
        self,
        mirror_webhooks: dict[str, str] | None = None,
        channel_ids: list[str] | None = None,
    ) -> SignalRouter:
        config = DiscordConfig(
            channel_ids=channel_ids or ["ch_chen_ge", "ch_san_ma_ge"],
            mirror_webhooks=mirror_webhooks or {},
        )
        return SignalRouter(
            discord_config=config,
            api_client=self.api_client,
            dry_run=True,         # dry_run=True 不真的發 trade，加速測試
            ai_parser=self.ai_parser,
        )

    @pytest.mark.asyncio
    async def test_mapped_channel_triggers_mirror_post(self):
        """訊息來自有 mapping 的 channel → POST 到對應 webhook URL。"""
        mirror_url = "https://discord.com/api/webhooks/123/abc"
        router = self._make_router(
            mirror_webhooks={"ch_chen_ge": mirror_url},
        )
        # _mirror_to_webhook 內部會用 aiohttp，patch 掉避免真實 network call
        with patch.object(router, "_mirror_to_webhook", new=AsyncMock()) as mock_mirror:
            await router.handle_message(_make_msg(channel_id="ch_chen_ge"))
            # asyncio.create_task 包了 coroutine — 等一個 tick 讓它跑
            await asyncio.sleep(0)
            mock_mirror.assert_awaited_once()
            args, _kwargs = mock_mirror.call_args
            assert args[0] == mirror_url
            # 訊息物件被傳進去（不限定欄位順序，看 _mirror_to_webhook 自己解）
            assert args[1]["channel_id"] == "ch_chen_ge"
            assert args[1]["content"] == "BTC long 60000"

    @pytest.mark.asyncio
    async def test_unmapped_channel_no_mirror(self):
        """通過 channel filter 但沒 mapping → 不 POST mirror。"""
        router = self._make_router(
            mirror_webhooks={"ch_chen_ge": "https://discord.com/api/webhooks/123/abc"},
            channel_ids=["ch_chen_ge", "ch_san_ma_ge"],
        )
        with patch.object(router, "_mirror_to_webhook", new=AsyncMock()) as mock_mirror:
            # ch_san_ma_ge 在 channel_ids 內但不在 mirror_webhooks → 不該 mirror
            await router.handle_message(_make_msg(channel_id="ch_san_ma_ge"))
            await asyncio.sleep(0)
            mock_mirror.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_empty_mirror_config_disables_feature(self):
        """mirror_webhooks 空 dict → 完全不 mirror（功能停用）。"""
        router = self._make_router(mirror_webhooks={})
        with patch.object(router, "_mirror_to_webhook", new=AsyncMock()) as mock_mirror:
            await router.handle_message(_make_msg(channel_id="ch_chen_ge"))
            await asyncio.sleep(0)
            mock_mirror.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_mirror_post_payload_includes_content_and_author(self):
        """POST 出去的 payload 必須含原文 + 來源作者（個人 server 才知道是誰發的）。"""
        mirror_url = "https://discord.com/api/webhooks/123/abc"
        router = self._make_router(mirror_webhooks={"ch_chen_ge": mirror_url})

        # 攔截 aiohttp.ClientSession.post 觀察 JSON body
        captured_payloads: list[dict] = []

        class _FakeResp:
            status = 204
            async def __aenter__(self):
                return self
            async def __aexit__(self, *args):
                return False
            async def text(self):
                return ""

        class _FakeSession:
            async def __aenter__(self):
                return self
            async def __aexit__(self, *args):
                return False
            def post(self, url, json=None, **kw):
                captured_payloads.append({"url": url, "json": json})
                return _FakeResp()

        with patch("src.signal_router.aiohttp.ClientSession", return_value=_FakeSession()):
            # 直接呼叫 _mirror_to_webhook（內部組 payload 後 POST）
            await router._mirror_to_webhook(
                mirror_url,
                _make_msg(content="BTC 60000 long SL 58000", author_name="陳哥"),
            )

        assert len(captured_payloads) == 1
        body = captured_payloads[0]["json"]
        # Discord webhook spec: 必須含 content；author 用 username 或寫進 content
        assert "BTC 60000 long SL 58000" in body.get("content", "")
        # 來源辨識：「陳哥」這個原始作者名稱要保留
        assert "陳哥" in body.get("content", "") or body.get("username") == "陳哥"

    @pytest.mark.asyncio
    async def test_mirror_post_failure_silently_swallowed(self):
        """webhook POST 失敗（network error / 4xx）→ log warning 但不 raise，主流程不影響。"""
        mirror_url = "https://discord.com/api/webhooks/123/abc"
        router = self._make_router(mirror_webhooks={"ch_chen_ge": mirror_url})

        class _ExplodingSession:
            async def __aenter__(self):
                return self
            async def __aexit__(self, *args):
                return False
            def post(self, *args, **kw):
                raise RuntimeError("simulated network failure")

        with patch("src.signal_router.aiohttp.ClientSession", return_value=_ExplodingSession()):
            # 不應該 raise — 內部 try/except 吞掉
            await router._mirror_to_webhook(mirror_url, _make_msg())
            # 沒 raise 就算 pass
