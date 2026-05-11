"""SignalRouter 複合動作（CLOSE+MOVE_SL）執行測試。"""
from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock, MagicMock

import pytest

from src.config import DiscordConfig
from src.signal_router import SignalRouter


def _make_msg(content: str, channel_id: str = "ch_chen_ge", message_id: str = "msg001") -> dict:
    return {
        "id": message_id,
        "channel_id": channel_id,
        "guild_id": "guild1",
        "author_id": "author_chen",
        "author_name": "陈哥合约频道",
        "content": content,
        "embeds": [],
        "timestamp": "2026-05-11T08:58:00",
        "has_reference": False,
        "has_snapshots": False,
    }


def _make_router(ai_parser) -> SignalRouter:
    discord_config = DiscordConfig(channel_ids=["ch_chen_ge"])
    api_client = MagicMock()
    from src.api_client import ExecutionResult
    api_client.send_trade = AsyncMock(return_value=ExecutionResult(success=True, status_code=200, summary="ok"))
    api_client.send_signal = AsyncMock(return_value=ExecutionResult(success=True, status_code=200, summary="ok"))
    return SignalRouter(
        discord_config=discord_config,
        api_client=api_client,
        ai_parser=ai_parser,
    )


class TestCompoundActionExecution:
    """確保 signal_router 收到 list 時送多筆 trade，每筆用 suffixed message_id。"""

    @pytest.mark.asyncio
    async def test_compound_sends_two_trades_with_suffixed_message_id(self):
        """[CLOSE, MOVE_SL] → 兩個 send_trade 呼叫，message_id 各自加 suffix"""
        ai_parser = MagicMock()
        ai_parser.parse = AsyncMock(return_value=[
            {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 0.5},
            {"action": "MOVE_SL", "symbol": "BTCUSDT"},
        ])
        ai_parser.prompt_version = 0
        router = _make_router(ai_parser)

        msg = _make_msg(
            content="中长线止盈50%做成本保护继续持有",
            message_id="msg_compound_001",
        )

        await router.handle_message(msg)

        # send_trade 該被呼叫 2 次
        assert router.api_client.send_trade.call_count == 2

        # 兩次呼叫 message_id 應該不同（一個 _close 一個 _movesl）
        call_args_list = router.api_client.send_trade.call_args_list
        message_ids = [call.kwargs["source"]["message_id"] for call in call_args_list]
        assert message_ids[0] != message_ids[1]
        assert any("close" in mid.lower() for mid in message_ids)
        assert any("movesl" in mid.lower() or "move_sl" in mid.lower() for mid in message_ids)

    @pytest.mark.asyncio
    async def test_compound_order_close_before_movesl(self):
        """順序：CLOSE 必須在 MOVE_SL 之前送（避免 race）"""
        ai_parser = MagicMock()
        ai_parser.parse = AsyncMock(return_value=[
            {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 0.5},
            {"action": "MOVE_SL", "symbol": "BTCUSDT"},
        ])
        ai_parser.prompt_version = 0
        router = _make_router(ai_parser)

        await router.handle_message(_make_msg("止盈50%做成本保護", message_id="msg002"))

        call_args_list = router.api_client.send_trade.call_args_list
        first_action = call_args_list[0].kwargs["trade_request"]["action"]
        second_action = call_args_list[1].kwargs["trade_request"]["action"]
        assert first_action == "CLOSE"
        assert second_action == "MOVE_SL"

    @pytest.mark.asyncio
    async def test_single_dict_still_works(self):
        """單一 dict 維持原行為（一個 send_trade 呼叫，message_id 不變）"""
        ai_parser = MagicMock()
        ai_parser.parse = AsyncMock(return_value={
            "action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 1.0,
        })
        ai_parser.prompt_version = 0
        router = _make_router(ai_parser)

        await router.handle_message(_make_msg("全部止盈出局", message_id="msg_single_001"))

        assert router.api_client.send_trade.call_count == 1
        source = router.api_client.send_trade.call_args.kwargs["source"]
        # 單動作不該 suffix
        assert source["message_id"] == "msg_single_001"


class TestCompoundE2E:
    """端對端：模擬陳哥真實 partial close + breakeven 訊息。"""

    @pytest.mark.asyncio
    async def test_chen_ge_realistic_message_flow(self):
        """陳哥 5/11 8:58 訊息的端對端模擬"""
        # 模擬 Gemini 對這則訊息的真實回應
        ai_parser = MagicMock()
        ai_parser.parse = AsyncMock(return_value=[
            {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 0.5},
            {"action": "MOVE_SL", "symbol": "BTCUSDT"},
        ])
        ai_parser.prompt_version = 7
        router = _make_router(ai_parser)

        # 真實訊息內容
        msg = _make_msg(
            content="🎉🎉🎉🎉🎉🎉\n再次恭喜跟上BTC空单的朋友\n短线收益止盈出局【1000收益点】\n中长线止盈50%做成本保护继续持有。\nBTC市价【81200】附近",
            message_id="real_msg_8_58",
        )

        await router.handle_message(msg)

        # 確認送了 2 個 trades
        assert router.api_client.send_trade.call_count == 2

        # 第一個是 CLOSE 50%
        call_1 = router.api_client.send_trade.call_args_list[0]
        req_1 = call_1.kwargs["trade_request"]
        src_1 = call_1.kwargs["source"]
        assert req_1["action"] == "CLOSE"
        assert req_1["close_ratio"] == 0.5
        assert req_1["symbol"] == "BTCUSDT"
        assert req_1.get("prompt_version") == 7
        assert src_1["message_id"] == "real_msg_8_58__close"

        # 第二個是 MOVE_SL（不帶 new_stop_loss → Java 端 breakeven）
        call_2 = router.api_client.send_trade.call_args_list[1]
        req_2 = call_2.kwargs["trade_request"]
        src_2 = call_2.kwargs["source"]
        assert req_2["action"] == "MOVE_SL"
        assert req_2["symbol"] == "BTCUSDT"
        # MOVE_SL 不帶 new_stop_loss — 由 Java 端用 entry price + 手續費補償
        assert "new_stop_loss" not in req_2 or req_2["new_stop_loss"] is None
        assert src_2["message_id"] == "real_msg_8_58__move_sl"


class TestImageCompoundFlow:
    """確保 image flow 也支援 compound action（不會 crash）"""

    @pytest.mark.asyncio
    async def test_image_compound_does_not_crash(self):
        """parse_with_image 回 list → _handle_image_signal 應該路由到 _forward_compound 不 crash"""
        # 需要 import + setup: 模擬 image_signal config 啟用
        from src.config import ImageSignalConfig
        from unittest.mock import patch

        ai_parser = MagicMock()
        ai_parser.parse_with_image = AsyncMock(return_value=[
            {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 0.5},
            {"action": "MOVE_SL", "symbol": "BTCUSDT"},
        ])
        ai_parser.parse = AsyncMock(return_value=None)
        ai_parser.prompt_version = 7

        discord_config = DiscordConfig(channel_ids=["ch_chen_ge"])
        image_config = ImageSignalConfig(
            enabled=True,
            dry_run=False,
            allowed_symbols=["BTCUSDT"],
        )
        api_client = MagicMock()
        from src.api_client import ExecutionResult
        api_client.send_trade = AsyncMock(
            return_value=ExecutionResult(success=True, status_code=200, summary="ok")
        )
        router = SignalRouter(
            discord_config=discord_config,
            api_client=api_client,
            ai_parser=ai_parser,
            image_signal_config=image_config,
        )

        msg = {
            "id": "img_compound_001",
            "channel_id": "ch_chen_ge",
            "guild_id": "guild1",
            "author_id": "author_chen",
            "author_name": "陈哥合约频道",
            "content": "",  # 純圖
            "embeds": [],
            "attachments": [{
                "id": "a1",
                "filename": "signal.png",
                "url": "https://cdn.discordapp.com/x.png",
                "content_type": "image/png",
                "size": 200000,
            }],
            "embed_images": [],
            "timestamp": "2026-05-12T10:00:00",
            "has_reference": False,
            "has_snapshots": False,
        }

        with patch("src.signal_router.fetch_image", new=AsyncMock(
            return_value=(b"\x89PNG\r\n\x1a\n", "image/png", "sha_img"),
        )):
            await router.handle_message(msg)

        # 應該 send 2 次（compound）
        assert api_client.send_trade.call_count == 2
        # 每次 message_id 該 suffix
        msg_ids = [c.kwargs["source"]["message_id"] for c in api_client.send_trade.call_args_list]
        assert any("close" in m.lower() for m in msg_ids)
        assert any("move_sl" in m.lower() for m in msg_ids)
