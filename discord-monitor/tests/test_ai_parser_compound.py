"""AI parser 複合動作（CLOSE + MOVE_SL）解析測試。"""
from __future__ import annotations

import json
import os
from unittest.mock import AsyncMock, MagicMock

import pytest

from src.ai_parser import AiSignalParser
from src.config import AiConfig


def _make_parser(response_obj) -> AiSignalParser:
    """建構帶 mock Gemini client 的 AiSignalParser。"""
    os.environ["GEMINI_API_KEY"] = "fake-key-for-test"
    config = AiConfig(enabled=True, model="gemini-2.0-flash", api_key_env="GEMINI_API_KEY")
    parser = AiSignalParser(config)

    mock_response = MagicMock()
    mock_response.text = json.dumps(response_obj) if not isinstance(response_obj, str) else response_obj
    mock_response.usage_metadata = MagicMock(
        prompt_token_count=500,
        candidates_token_count=50,
        total_token_count=550,
    )

    mock_models = MagicMock()
    mock_models.generate_content = AsyncMock(return_value=mock_response)
    mock_aio = MagicMock()
    mock_aio.models = mock_models
    mock_client = MagicMock()
    mock_client.aio = mock_aio
    parser.client = mock_client
    return parser


class TestCompoundActionParsing:
    """複合動作解析測試 — 確保 parse() 對合法 compound 回 list，不合法時退回 pick best。"""

    @pytest.mark.asyncio
    async def test_compound_close_movesl_returns_list(self):
        """合法的 [CLOSE, MOVE_SL] compound → parse 應該回 list（不是 pick best 拿走一個）"""
        parser = _make_parser([
            {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 0.5},
            {"action": "MOVE_SL", "symbol": "BTCUSDT"},
        ])

        result = await parser.parse("中长线止盈50%做成本保护继续持有")

        assert isinstance(result, list), f"expected list, got {type(result)}"
        assert len(result) == 2
        actions = sorted([a["action"] for a in result])
        assert actions == ["CLOSE", "MOVE_SL"]

    @pytest.mark.asyncio
    async def test_cross_symbol_close_entry_falls_back(self):
        """跨幣 [ENTRY BTC, CLOSE ETH] → 不是換手 compound（不同幣）→ 退回 pick best 拿單一動作。

        注意：同幣 [CLOSE, ENTRY] 現在是合法換手 compound（見 TestCompoundCloseEntry），
        這個 case 之所以 fallback 純粹因為跨幣。
        """
        parser = _make_parser([
            {"action": "ENTRY", "symbol": "BTCUSDT", "side": "SHORT", "entry_price": 82000},
            {"action": "CLOSE", "symbol": "ETHUSDT", "close_ratio": 1.0},
        ])

        result = await parser.parse("某混亂訊息")

        # 跨幣 → 不算 compound → _pick_best_from_list 挑 ENTRY（priority 5 > CLOSE 4）
        assert isinstance(result, dict), f"expected dict, got {type(result)}"
        assert result["action"] == "ENTRY"

    @pytest.mark.asyncio
    async def test_compound_with_three_items_falls_back_to_pick_best(self):
        """3 個動作的 list → 不是 compound（compound 一定是恰好 2 個）→ 退回 pick best"""
        parser = _make_parser([
            {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 0.5},
            {"action": "MOVE_SL", "symbol": "BTCUSDT"},
            {"action": "INFO", "symbol": "BTCUSDT"},
        ])

        result = await parser.parse("超複雜訊息")

        # 不應該回 list（因為不是純 compound）
        assert isinstance(result, dict), f"expected dict, got {type(result)}"

    @pytest.mark.asyncio
    async def test_single_close_stays_single(self):
        """單一 CLOSE dict → 維持 dict 回傳（不變 list）"""
        parser = _make_parser({"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 0.5})

        result = await parser.parse("止盈50%")

        assert isinstance(result, dict)
        assert result["action"] == "CLOSE"

    @pytest.mark.asyncio
    async def test_compound_with_invalid_movesl_falls_back(self):
        """compound 但 MOVE_SL 帶了不合法欄位 → validate fail → 退回 pick best"""
        parser = _make_parser([
            {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 0.5},
            {"action": "MOVE_SL"},  # 缺 symbol — validate fail
        ])

        result = await parser.parse("止盈50%做成本保護")

        # MOVE_SL 沒過 validate → 不算合法 compound → 退回 pick best
        assert not isinstance(result, list)


class TestCompoundDefenseInDepth:
    """Compound action 防禦層測試 — 不該被 false positive 觸發的 case。"""

    @pytest.mark.asyncio
    async def test_compound_cross_symbol_falls_back(self):
        """[CLOSE BTC, MOVE_SL ETH] 跨幣 → 不視為 compound，退回 pick best"""
        parser = _make_parser([
            {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 0.5},
            {"action": "MOVE_SL", "symbol": "ETHUSDT"},
        ])

        result = await parser.parse("BTC 止盈50% ETH 移SL到入場")

        # 跨幣 → 不該回 list
        assert not isinstance(result, list), \
            f"cross-symbol must not be compound, got list: {result}"

    @pytest.mark.asyncio
    async def test_two_close_no_movesl_falls_back(self):
        """[CLOSE 50%, CLOSE 100%] 兩個 CLOSE 沒 MOVE_SL → 不視為 compound"""
        parser = _make_parser([
            {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 0.5},
            {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 1.0},
        ])

        result = await parser.parse("某混亂訊息")

        # 兩個 CLOSE 沒 MOVE_SL → 不是 compound
        assert not isinstance(result, list)

    @pytest.mark.asyncio
    async def test_invalid_close_ratio_falls_back(self):
        """[CLOSE 1.5, MOVE_SL] close_ratio 超範圍 → 不視為 compound"""
        parser = _make_parser([
            {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 1.5},
            {"action": "MOVE_SL", "symbol": "BTCUSDT"},
        ])

        result = await parser.parse("止盈一半半半做成本保護")

        # close_ratio > 1 → 不是 compound
        assert not isinstance(result, list)


class TestCompoundCloseEntry:
    """換手 / 反手複合動作（CLOSE + ENTRY）— Issue: 2026-05-29 換手做空裸倉事件。

    舊行為：[CLOSE, ENTRY] 不被當 compound → _pick_best_from_list 只挑 ENTRY → 丟掉 CLOSE
    → 用戶原倉沒平就被「已有持倉拒絕開倉」擋下。
    新行為：同幣 [CLOSE, ENTRY] → 合法 compound，回 ordered list（CLOSE 先送、ENTRY 後送）。
    """

    @pytest.mark.asyncio
    async def test_close_entry_returns_ordered_list(self):
        """同幣 [ENTRY, CLOSE]（順序顛倒）→ 回 ordered list，CLOSE 必排在 ENTRY 前。"""
        parser = _make_parser([
            {"action": "ENTRY", "symbol": "BTCUSDT", "side": "SHORT", "entry_price": 73300, "stop_loss": 74500},
            {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": None},
        ])

        result = await parser.parse("多单全部止盈出局，换手做空73300，止损74500")

        assert isinstance(result, list), f"expected list, got {type(result)}"
        assert len(result) == 2
        assert result[0]["action"] == "CLOSE", "CLOSE 必須先送（先平原倉），否則反向開倉被擋"
        assert result[1]["action"] == "ENTRY"
        assert result[1]["side"] == "SHORT"
        assert result[1]["entry_price"] == 73300

    @pytest.mark.asyncio
    async def test_close_entry_long_switch(self):
        """換多：[CLOSE, ENTRY LONG] 同幣 → compound list。"""
        parser = _make_parser([
            {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": None},
            {"action": "ENTRY", "symbol": "BTCUSDT", "side": "LONG", "entry_price": 95000, "stop_loss": 93500},
        ])

        result = await parser.parse("空单先平掉，换多95000，止损93500")

        assert isinstance(result, list)
        assert [a["action"] for a in result] == ["CLOSE", "ENTRY"]
        assert result[1]["side"] == "LONG"

    @pytest.mark.asyncio
    async def test_close_entry_cross_symbol_falls_back(self):
        """跨幣 [CLOSE BTC, ENTRY ETH] → 不是換手（不同幣）→ 退回 pick best。"""
        parser = _make_parser([
            {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": None},
            {"action": "ENTRY", "symbol": "ETHUSDT", "side": "SHORT", "entry_price": 2950, "stop_loss": 3050},
        ])

        result = await parser.parse("BTC平掉 ETH做空2950")

        assert not isinstance(result, list), f"cross-symbol must not be compound: {result}"

    @pytest.mark.asyncio
    async def test_close_entry_missing_entry_price_falls_back(self):
        """ENTRY 缺 entry_price → _validate fail → 不是合法 compound → 退回 pick best。"""
        parser = _make_parser([
            {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": None},
            {"action": "ENTRY", "symbol": "BTCUSDT", "side": "SHORT"},  # 缺 entry_price
        ])

        result = await parser.parse("平掉换手做空")

        assert not isinstance(result, list)

    @pytest.mark.asyncio
    async def test_close_entry_missing_side_falls_back(self):
        """ENTRY 缺 side → _validate fail → 不是合法 compound → 退回 pick best。"""
        parser = _make_parser([
            {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": None},
            {"action": "ENTRY", "symbol": "BTCUSDT", "entry_price": 73300},  # 缺 side
        ])

        result = await parser.parse("平掉换手 73300")

        assert not isinstance(result, list)

    @pytest.mark.asyncio
    async def test_close_entry_with_full_close_ratio(self):
        """換手 CLOSE 帶 close_ratio=1.0（全平）也是合法 compound（null 與 1.0 皆全平）。"""
        parser = _make_parser([
            {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 1.0},
            {"action": "ENTRY", "symbol": "BTCUSDT", "side": "SHORT", "entry_price": 73300, "stop_loss": 74500},
        ])

        result = await parser.parse("多单全部止盈，换手做空73300，止损74500")

        assert isinstance(result, list)
        assert [a["action"] for a in result] == ["CLOSE", "ENTRY"]

    @pytest.mark.asyncio
    async def test_switch_without_entry_info_stays_single_close(self):
        """只講『換手』沒給新倉進場價 → Gemini 回單一 CLOSE dict → 維持單一（新單下條訊息再進）。"""
        parser = _make_parser({"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": None})

        result = await parser.parse("多单先全部止盈出局，准备换手，稍后发新单")

        assert isinstance(result, dict)
        assert result["action"] == "CLOSE"
