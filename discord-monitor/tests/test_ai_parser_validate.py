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

    def test_entry_missing_stop_loss(self):
        parsed = {
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "LONG",
            "entry_price": 95000,
        }
        assert AiSignalParser._validate(None, parsed) is False

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

    def test_entry_zero_stop_loss_is_falsy(self):
        """stop_loss=0 is falsy → validation should fail."""
        parsed = {
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "LONG",
            "entry_price": 95000,
            "stop_loss": 0,
        }
        assert AiSignalParser._validate(None, parsed) is False

    def test_entry_zero_entry_price_is_falsy(self):
        parsed = {
            "action": "ENTRY",
            "symbol": "BTCUSDT",
            "side": "LONG",
            "entry_price": 0,
            "stop_loss": 93000,
        }
        assert AiSignalParser._validate(None, parsed) is False


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

    def test_move_sl_missing_both(self):
        parsed = {"action": "MOVE_SL", "symbol": "BTCUSDT"}
        assert AiSignalParser._validate(None, parsed) is False

    def test_move_sl_both_null(self):
        parsed = {
            "action": "MOVE_SL",
            "symbol": "BTCUSDT",
            "new_stop_loss": None,
            "new_take_profit": None,
        }
        assert AiSignalParser._validate(None, parsed) is False

    def test_move_sl_zero_values(self):
        """0 is falsy → should fail."""
        parsed = {
            "action": "MOVE_SL",
            "symbol": "BTCUSDT",
            "new_stop_loss": 0,
            "new_take_profit": 0,
        }
        assert AiSignalParser._validate(None, parsed) is False


class TestValidateClose:
    """CLOSE action validation."""

    def test_valid_close(self):
        parsed = {"action": "CLOSE", "symbol": "BTCUSDT", "side": "SHORT"}
        assert AiSignalParser._validate(None, parsed) is True

    def test_close_only_symbol(self):
        parsed = {"action": "CLOSE", "symbol": "BTCUSDT"}
        assert AiSignalParser._validate(None, parsed) is True


class TestValidateInfo:
    """INFO action validation."""

    def test_valid_info(self):
        parsed = {"action": "INFO", "symbol": "BTCUSDT"}
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

    def test_missing_symbol(self):
        parsed = {"action": "ENTRY", "side": "LONG", "entry_price": 95000, "stop_loss": 93000}
        assert AiSignalParser._validate(None, parsed) is False

    def test_empty_action(self):
        parsed = {"action": "", "symbol": "BTCUSDT"}
        assert AiSignalParser._validate(None, parsed) is False

    def test_empty_symbol(self):
        parsed = {"action": "ENTRY", "symbol": ""}
        assert AiSignalParser._validate(None, parsed) is False

    def test_unknown_action(self):
        parsed = {"action": "FOOBAR", "symbol": "BTCUSDT"}
        assert AiSignalParser._validate(None, parsed) is False
