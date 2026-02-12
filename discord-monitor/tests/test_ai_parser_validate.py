"""Tests for AiSignalParser._validate() — validation logic only, no Gemini API calls."""

import pytest
from src.ai_parser import AiSignalParser


class TestValidateEntry:
    """ENTRY action validation."""

    def test_valid_entry_long(self):
        parsed = {
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "LONG",
            "entry_price": 95000,
            "stop_loss": 93000,
            "take_profit": 98000,
        }
        assert AiSignalParser._validate(None, parsed) is True

    def test_valid_entry_short(self):
        parsed = {
            "action": "ENTRY",
            "symbol": "ETHUSDT",
            "side": "SHORT",
            "entry_price": 2650,
            "stop_loss": 2750,
            "take_profit": 2400,
        }
        assert AiSignalParser._validate(None, parsed) is True

    def test_entry_missing_side(self):
        parsed = {
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "entry_price": 95000,
            "stop_loss": 93000,
        }
        assert AiSignalParser._validate(None, parsed) is False

    def test_entry_invalid_side(self):
        parsed = {
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "UP",
            "entry_price": 95000,
            "stop_loss": 93000,
        }
        assert AiSignalParser._validate(None, parsed) is False

    def test_entry_missing_entry_price(self):
        parsed = {
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "LONG",
            "stop_loss": 93000,
        }
        assert AiSignalParser._validate(None, parsed) is False

    def test_entry_without_stop_loss_still_valid(self):
        """陳哥有時不給 SL，ENTRY 只需 side + entry_price。"""
        parsed = {
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "LONG",
            "entry_price": 95000,
        }
        assert AiSignalParser._validate(None, parsed) is True

    def test_entry_no_take_profit_still_valid(self):
        """ENTRY without TP is valid (Java fills default)."""
        parsed = {
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "LONG",
            "entry_price": 95000,
            "stop_loss": 93000,
        }
        assert AiSignalParser._validate(None, parsed) is True

    def test_entry_zero_entry_price_is_falsy(self):
        parsed = {
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "LONG",
            "entry_price": 0,
            "stop_loss": 93000,
        }
        assert AiSignalParser._validate(None, parsed) is False

    def test_entry_market_order_without_sl(self):
        """市價單通常沒 SL，之後單獨給。"""
        parsed = {
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "LONG",
            "entry_price": 91200,
        }
        assert AiSignalParser._validate(None, parsed) is True


class TestValidateCancel:
    """CANCEL action validation."""

    def test_valid_cancel(self):
        parsed = {"action": "CANCEL", "symbol": "ETHUSDT", "side": "SHORT"}
        assert AiSignalParser._validate(None, parsed) is True

    def test_cancel_only_symbol_needed(self):
        parsed = {"action": "CANCEL", "symbol": "BTCUSDT"}
        assert AiSignalParser._validate(None, parsed) is True


class TestValidateMoveSl:
    """MOVE_SL action validation."""

    def test_valid_move_sl_both(self):
        parsed = {
            "action": "MOVE_SL",
            "symbol": "BTCUSDT",
            "new_stop_loss": 65000,
            "new_take_profit": 69200,
        }
        assert AiSignalParser._validate(None, parsed) is True

    def test_valid_move_sl_only_sl(self):
        parsed = {
            "action": "MOVE_SL",
            "symbol": "BTCUSDT",
            "new_stop_loss": 65000,
        }
        assert AiSignalParser._validate(None, parsed) is True

    def test_valid_move_sl_only_tp(self):
        parsed = {
            "action": "MOVE_SL",
            "symbol": "BTCUSDT",
            "new_take_profit": 69200,
        }
        assert AiSignalParser._validate(None, parsed) is True

    def test_move_sl_no_price_still_valid(self):
        """成本保護不帶具體價格也合法，Java 端處理。"""
        parsed = {"action": "MOVE_SL", "symbol": "BTCUSDT"}
        assert AiSignalParser._validate(None, parsed) is True

    def test_move_sl_with_nulls_still_valid(self):
        """成本保護場景：AI 回 null 價格。"""
        parsed = {
            "action": "MOVE_SL",
            "symbol": "BTCUSDT",
            "new_stop_loss": None,
            "new_take_profit": None,
        }
        assert AiSignalParser._validate(None, parsed) is True


class TestValidateClose:
    """CLOSE action validation."""

    def test_valid_close(self):
        parsed = {"action": "CLOSE", "symbol": "BTCUSDT", "side": "SHORT"}
        assert AiSignalParser._validate(None, parsed) is True

    def test_close_only_symbol(self):
        parsed = {"action": "CLOSE", "symbol": "BTCUSDT"}
        assert AiSignalParser._validate(None, parsed) is True

    def test_close_with_valid_ratio(self):
        parsed = {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 0.5}
        assert AiSignalParser._validate(None, parsed) is True

    def test_close_ratio_full(self):
        parsed = {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 1.0}
        assert AiSignalParser._validate(None, parsed) is True

    def test_close_ratio_null_means_full(self):
        parsed = {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": None}
        assert AiSignalParser._validate(None, parsed) is True

    def test_close_ratio_zero_invalid(self):
        """0 = 不平任何倉位，沒意義。"""
        parsed = {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 0}
        assert AiSignalParser._validate(None, parsed) is False

    def test_close_ratio_negative_invalid(self):
        parsed = {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": -0.5}
        assert AiSignalParser._validate(None, parsed) is False

    def test_close_ratio_over_one_invalid(self):
        parsed = {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": 1.5}
        assert AiSignalParser._validate(None, parsed) is False

    def test_close_ratio_string_invalid(self):
        parsed = {"action": "CLOSE", "symbol": "BTCUSDT", "close_ratio": "half"}
        assert AiSignalParser._validate(None, parsed) is False


class TestValidateInfo:
    """INFO action validation."""

    def test_valid_info_with_symbol(self):
        parsed = {"action": "INFO", "symbol": "BTCUSDT"}
        assert AiSignalParser._validate(None, parsed) is True

    def test_info_without_symbol(self):
        """閒聊訊息可能沒有 symbol，仍合法。"""
        parsed = {"action": "INFO"}
        assert AiSignalParser._validate(None, parsed) is True

    def test_info_empty_dict_with_action(self):
        parsed = {"action": "INFO", "symbol": None}
        assert AiSignalParser._validate(None, parsed) is True


class TestValidateSymbol:
    """Symbol auto-fix and missing field checks."""

    def test_auto_append_usdt(self):
        parsed = {"action": "CANCEL", "symbol": "BTC"}
        AiSignalParser._validate(None, parsed)
        assert parsed["symbol"] == "BTCUSDT"

    def test_symbol_already_has_usdt(self):
        parsed = {"action": "CANCEL", "symbol": "ETHUSDT"}
        AiSignalParser._validate(None, parsed)
        assert parsed["symbol"] == "ETHUSDT"

    def test_missing_action(self):
        parsed = {"symbol": "BTCUSDT"}
        assert AiSignalParser._validate(None, parsed) is False

    def test_missing_symbol_non_info(self):
        """非 INFO 缺少 symbol → False。"""
        parsed = {"action": "ENTRY", "side": "LONG", "entry_price": 95000}
        assert AiSignalParser._validate(None, parsed) is False

    def test_empty_action(self):
        parsed = {"action": "", "symbol": "BTCUSDT"}
        assert AiSignalParser._validate(None, parsed) is False

    def test_empty_symbol_non_info(self):
        parsed = {"action": "ENTRY", "symbol": ""}
        assert AiSignalParser._validate(None, parsed) is False

    def test_unknown_action(self):
        parsed = {"action": "FOOBAR", "symbol": "BTCUSDT"}
        assert AiSignalParser._validate(None, parsed) is False
