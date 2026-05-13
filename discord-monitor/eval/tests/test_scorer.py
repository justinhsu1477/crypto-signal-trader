"""Unit tests for the scorer — provider-independent (no API calls)."""
from __future__ import annotations

import pytest

from eval.scorer import score_case


def test_exact_match_returns_1():
    case = {
        "expected": {
            "action": "ENTRY", "symbol": "BTCUSDT", "side": "SHORT",
            "entry_price": 82200, "stop_loss": 83800,
        }
    }
    actual = {
        "action": "ENTRY", "symbol": "BTCUSDT", "side": "SHORT",
        "entry_price": 82200, "stop_loss": 83800,
    }
    score, failures = score_case(case, actual)
    assert score == 1.0
    assert failures == []


def test_action_mismatch_zero():
    case = {"expected": {"action": "ENTRY", "symbol": "BTCUSDT"}}
    actual = {"action": "CLOSE", "symbol": "BTCUSDT"}
    score, failures = score_case(case, actual)
    assert score == 0.0
    assert "action" in failures[0]


def test_symbol_mismatch_zero():
    case = {"expected": {"action": "ENTRY", "symbol": "BTCUSDT"}}
    actual = {"action": "ENTRY", "symbol": "ETHUSDT"}
    score, failures = score_case(case, actual)
    assert score == 0.0
    assert "symbol" in failures[0]


def test_side_mismatch_partial_penalty():
    case = {"expected": {
        "action": "ENTRY", "symbol": "BTCUSDT", "side": "SHORT",
        "entry_price": 80000, "stop_loss": 82000,
    }}
    actual = {
        "action": "ENTRY", "symbol": "BTCUSDT", "side": "LONG",
        "entry_price": 80000, "stop_loss": 82000,
    }
    score, failures = score_case(case, actual)
    assert 0.6 < score < 0.8
    assert any("side" in f for f in failures)


def test_entry_price_tolerance():
    case = {
        "expected": {
            "action": "ENTRY", "symbol": "BTCUSDT", "side": "SHORT",
            "entry_price": 80000, "stop_loss": 82000,
        },
        "tolerance_pct": 1.0,
    }
    # 0.5% off — within tolerance
    actual = {
        "action": "ENTRY", "symbol": "BTCUSDT", "side": "SHORT",
        "entry_price": 80400, "stop_loss": 82000,
    }
    score, _ = score_case(case, actual)
    assert score == 1.0

    # 2% off — outside tolerance
    actual2 = {
        "action": "ENTRY", "symbol": "BTCUSDT", "side": "SHORT",
        "entry_price": 81600, "stop_loss": 82000,
    }
    score2, failures2 = score_case(case, actual2)
    assert 0.7 < score2 < 0.9
    assert any("entry_price" in f for f in failures2)


def test_info_expected_info_actual():
    case = {"expected": {"action": "INFO"}}
    actual = {"action": "INFO"}
    score, _ = score_case(case, actual)
    assert score == 1.0


def test_info_expected_none_actual():
    case = {"expected": {"action": "INFO"}}
    score, _ = score_case(case, None)
    # parser returning None for non-signal is OK — treated as INFO
    assert score == 1.0


def test_compound_perfect():
    case = {"expected_list": [
        {"action": "CLOSE", "close_ratio": 0.5},
        {"action": "MOVE_SL"},
    ]}
    actual = [
        {"action": "CLOSE", "close_ratio": 0.5, "symbol": "BTCUSDT"},
        {"action": "MOVE_SL", "symbol": "BTCUSDT"},
    ]
    score, _ = score_case(case, actual)
    assert score == 1.0


def test_compound_wrong_ratio():
    case = {"expected_list": [
        {"action": "CLOSE", "close_ratio": 0.5},
        {"action": "MOVE_SL"},
    ]}
    actual = [
        {"action": "CLOSE", "close_ratio": 0.3},
        {"action": "MOVE_SL"},
    ]
    score, _ = score_case(case, actual)
    assert 0.6 < score < 0.9


def test_compound_dict_instead_of_list_zero():
    """Expected compound, parser returned single dict → 0."""
    case = {"expected_list": [
        {"action": "CLOSE"},
        {"action": "MOVE_SL"},
    ]}
    actual = {"action": "CLOSE"}
    score, failures = score_case(case, actual)
    assert score == 0.0
    assert "expected list" in failures[0]


def test_none_actual_for_entry_zero():
    case = {"expected": {"action": "ENTRY", "symbol": "BTCUSDT"}}
    score, failures = score_case(case, None)
    assert score == 0.0
    assert "None" in failures[0]
