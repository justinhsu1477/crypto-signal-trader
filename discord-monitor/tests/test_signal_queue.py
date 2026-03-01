"""Tests for SignalQueue — 本地失敗訊號佇列。

覆蓋：
  - 基本操作（enqueue / dequeue / peek / size）
  - 上限控制（max_size）
  - 過期清除（max_age_hours）
  - 損毀容錯（corrupt JSON / missing file）
  - 重啟持久化（cross-instance persistence）
  - 原子寫入（atomic write with tmp file）
  - 重播計數（increment_attempt）
  - Router 整合（API fail → enqueue / API 4xx → no enqueue）
  - Replay 邏輯（success → dequeue / 5xx → stop / 4xx → remove）
"""
from __future__ import annotations

import asyncio
import json
import os
import time
from datetime import datetime, timedelta
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch
from zoneinfo import ZoneInfo

import pytest

from src.signal_queue import QueueConfig, SignalQueue

TZ = ZoneInfo("Asia/Taipei")


@pytest.fixture
def queue_dir(tmp_path):
    """每個測試獨立的 queue 目錄。"""
    return str(tmp_path / "data")


@pytest.fixture
def config(queue_dir):
    return QueueConfig(
        enabled=True,
        queue_dir=queue_dir,
        max_size=100,
        max_age_hours=24,
        max_replay_attempts=5,
    )


@pytest.fixture
def queue(config):
    return SignalQueue(config)


def _make_payload(action="ENTRY", symbol="BTCUSDT"):
    return {"action": action, "symbol": symbol, "side": "SHORT", "entry_price": 67800}


def _make_source(message_id="msg_001"):
    return {"platform": "DISCORD", "author_name": "Test", "message_id": message_id}


# ==================== 基本操作 ====================

class TestBasicOperations:

    def test_enqueue_and_peek(self, queue):
        """存入後可讀取。"""
        result = queue.enqueue("send_trade", _make_payload(), _make_source())
        assert result is True
        entries = queue.peek_all()
        assert len(entries) == 1
        assert entries[0]["call_type"] == "send_trade"
        assert entries[0]["payload"]["symbol"] == "BTCUSDT"
        assert entries[0]["source"]["message_id"] == "msg_001"
        assert entries[0]["attempt_count"] == 0

    def test_dequeue_removes_entry(self, queue):
        """移除後消失。"""
        queue.enqueue("send_trade", _make_payload(), _make_source())
        entries = queue.peek_all()
        entry_id = entries[0]["id"]

        queue.dequeue(entry_id)
        assert queue.size() == 0

    def test_dequeue_nonexistent_id_is_safe(self, queue):
        """移除不存在的 ID 不報錯。"""
        queue.enqueue("send_trade", _make_payload(), _make_source())
        queue.dequeue("nonexistent_id")
        assert queue.size() == 1  # 未被影響

    def test_size_returns_count(self, queue):
        """size() 正確回傳數量。"""
        assert queue.size() == 0
        queue.enqueue("send_trade", _make_payload(), _make_source("msg1"))
        assert queue.size() == 1
        queue.enqueue("send_trade", _make_payload(), _make_source("msg2"))
        assert queue.size() == 2

    def test_fifo_order(self, queue):
        """peek_all 回傳 FIFO 順序（最舊的先）。"""
        queue.enqueue("send_trade", _make_payload("ENTRY"), _make_source("msg1"))
        queue.enqueue("send_trade", _make_payload("CLOSE"), _make_source("msg2"))
        entries = queue.peek_all()
        assert entries[0]["payload"]["action"] == "ENTRY"
        assert entries[1]["payload"]["action"] == "CLOSE"

    def test_enqueue_both_call_types(self, queue):
        """支援 send_trade 和 send_signal 兩種 call_type。"""
        queue.enqueue("send_trade", _make_payload(), _make_source("msg1"))
        queue.enqueue("send_signal", {"message": "BTC SHORT 68000"}, _make_source("msg2"))
        entries = queue.peek_all()
        assert entries[0]["call_type"] == "send_trade"
        assert entries[1]["call_type"] == "send_signal"
        assert entries[1]["payload"]["message"] == "BTC SHORT 68000"


# ==================== 上限控制 ====================

class TestMaxSize:

    def test_max_size_enforced(self, queue_dir):
        """超過 max_size 拒絕存入。"""
        config = QueueConfig(queue_dir=queue_dir, max_size=3)
        q = SignalQueue(config)

        assert q.enqueue("send_trade", _make_payload(), _make_source("m1")) is True
        assert q.enqueue("send_trade", _make_payload(), _make_source("m2")) is True
        assert q.enqueue("send_trade", _make_payload(), _make_source("m3")) is True
        assert q.enqueue("send_trade", _make_payload(), _make_source("m4")) is False  # 第 4 筆被拒
        assert q.size() == 3


# ==================== 過期清除 ====================

class TestExpiry:

    def test_expired_entries_pruned(self, queue_dir):
        """超過 max_age_hours 的項目被自動清除。"""
        config = QueueConfig(queue_dir=queue_dir, max_age_hours=1)
        q = SignalQueue(config)

        # 手動寫入一筆 2 小時前的紀錄
        old_entry = {
            "id": "sq_old",
            "queued_at": (datetime.now(TZ) - timedelta(hours=2)).isoformat(),
            "call_type": "send_trade",
            "payload": _make_payload(),
            "source": _make_source(),
            "dry_run": False,
            "attempt_count": 0,
            "last_attempt_at": None,
            "original_content": "",
        }
        queue_file = Path(queue_dir) / "signal_queue.json"
        os.makedirs(queue_dir, exist_ok=True)
        with open(queue_file, "w") as f:
            json.dump([old_entry], f)

        # peek_all 應該自動清除過期
        entries = q.peek_all()
        assert len(entries) == 0

    def test_non_expired_entries_kept(self, queue_dir):
        """未過期的項目保留。"""
        config = QueueConfig(queue_dir=queue_dir, max_age_hours=24)
        q = SignalQueue(config)
        q.enqueue("send_trade", _make_payload(), _make_source())
        entries = q.peek_all()
        assert len(entries) == 1


# ==================== 損毀容錯 ====================

class TestCorruptionResilience:

    def test_corrupt_json_returns_empty(self, queue_dir):
        """損毀 JSON 不 crash，回傳空 list。"""
        os.makedirs(queue_dir, exist_ok=True)
        queue_file = Path(queue_dir) / "signal_queue.json"
        with open(queue_file, "w") as f:
            f.write("THIS IS NOT VALID JSON {{{")

        q = SignalQueue(QueueConfig(queue_dir=queue_dir))
        entries = q.peek_all()
        assert entries == []

    def test_missing_file_returns_empty(self, queue_dir):
        """檔案不存在回傳空 list。"""
        q = SignalQueue(QueueConfig(queue_dir=queue_dir))
        assert q.size() == 0
        assert q.peek_all() == []

    def test_non_array_json_returns_empty(self, queue_dir):
        """JSON 不是 array → 回傳空 list。"""
        os.makedirs(queue_dir, exist_ok=True)
        queue_file = Path(queue_dir) / "signal_queue.json"
        with open(queue_file, "w") as f:
            json.dump({"not": "an array"}, f)

        q = SignalQueue(QueueConfig(queue_dir=queue_dir))
        assert q.peek_all() == []


# ==================== 重啟持久化 ====================

class TestPersistence:

    def test_persist_across_instances(self, queue_dir):
        """不同 SignalQueue 實例可讀取同一份佇列檔案。"""
        config = QueueConfig(queue_dir=queue_dir)
        q1 = SignalQueue(config)
        q1.enqueue("send_trade", _make_payload(), _make_source())

        # 模擬 Monitor 重啟 → 新的 SignalQueue 實例
        q2 = SignalQueue(config)
        assert q2.size() == 1
        entries = q2.peek_all()
        assert entries[0]["payload"]["symbol"] == "BTCUSDT"


# ==================== 重播計數 ====================

class TestIncrementAttempt:

    def test_increment_attempt(self, queue):
        """累加重播次數和更新時間戳。"""
        queue.enqueue("send_trade", _make_payload(), _make_source())
        entry_id = queue.peek_all()[0]["id"]

        queue.increment_attempt(entry_id)
        entries = queue.peek_all()
        assert entries[0]["attempt_count"] == 1
        assert entries[0]["last_attempt_at"] is not None

        queue.increment_attempt(entry_id)
        entries = queue.peek_all()
        assert entries[0]["attempt_count"] == 2


# ==================== ID 唯一性 ====================

class TestIdGeneration:

    def test_generate_id_uniqueness(self):
        """連續產生的 ID 都不重複。"""
        ids = set()
        for _ in range(100):
            ids.add(SignalQueue._generate_id())
        assert len(ids) == 100

    def test_id_format(self):
        """ID 格式: sq_{unix_ts}_{hex6}"""
        entry_id = SignalQueue._generate_id()
        assert entry_id.startswith("sq_")
        parts = entry_id.split("_")
        assert len(parts) == 3
        assert parts[1].isdigit()
        assert len(parts[2]) == 6


# ==================== Router 整合 ====================

class TestRouterIntegration:
    """signal_router 呼叫 enqueue 的場景。"""

    @pytest.mark.asyncio
    async def test_api_failure_enqueues_signal(self):
        """API fail (status_code=0) → 自動存入 queue。"""
        from src.api_client import ExecutionResult
        from src.signal_router import SignalRouter
        from src.config import DiscordConfig

        mock_api = AsyncMock()
        # ai_parser=None → regex fallback → 呼叫 send_signal（不是 send_trade）
        mock_api.send_signal = AsyncMock(return_value=ExecutionResult(
            success=False, status_code=0, summary="", error="All 3 retries failed",
        ))

        mock_queue = MagicMock()
        mock_queue.enqueue = MagicMock(return_value=True)

        router = SignalRouter(
            DiscordConfig(channel_ids=["ch1"]),
            mock_api,
            ai_parser=None,
            signal_queue=mock_queue,
        )

        # 直接呼叫 _forward_signal（不經過 handle_message 的 filter）
        await router._forward_signal("BTC SHORT 68000", source={"platform": "DISCORD", "message_id": "m1"})

        # send_signal 失敗 → enqueue 被呼叫
        mock_queue.enqueue.assert_called_once()
        call_args = mock_queue.enqueue.call_args
        assert call_args.kwargs["call_type"] == "send_signal"

    @pytest.mark.asyncio
    async def test_api_4xx_no_enqueue(self):
        """API 4xx → 不存 queue（client 錯誤，重播也失敗）。"""
        from src.api_client import ExecutionResult
        from src.signal_router import SignalRouter
        from src.config import DiscordConfig

        mock_api = AsyncMock()
        mock_api.send_signal = AsyncMock(return_value=ExecutionResult(
            success=False, status_code=400, summary="", error="bad request",
        ))

        mock_queue = MagicMock()
        router = SignalRouter(
            DiscordConfig(channel_ids=["ch1"]),
            mock_api,
            ai_parser=None,
            signal_queue=mock_queue,
        )

        await router._forward_signal("invalid signal")

        mock_queue.enqueue.assert_not_called()

    @pytest.mark.asyncio
    async def test_api_success_no_enqueue(self):
        """API 成功 → 不存 queue。"""
        from src.api_client import ExecutionResult
        from src.signal_router import SignalRouter
        from src.config import DiscordConfig

        mock_api = AsyncMock()
        mock_api.send_signal = AsyncMock(return_value=ExecutionResult(
            success=True, status_code=200, summary="ok",
        ))

        mock_queue = MagicMock()
        router = SignalRouter(
            DiscordConfig(channel_ids=["ch1"]),
            mock_api,
            ai_parser=None,
            signal_queue=mock_queue,
        )

        await router._forward_signal("BTC LONG 70000")

        mock_queue.enqueue.assert_not_called()

    @pytest.mark.asyncio
    async def test_no_queue_configured_still_works(self):
        """signal_queue=None 時，API 失敗只 log 不報錯。"""
        from src.api_client import ExecutionResult
        from src.signal_router import SignalRouter
        from src.config import DiscordConfig

        mock_api = AsyncMock()
        mock_api.send_signal = AsyncMock(return_value=ExecutionResult(
            success=False, status_code=0, summary="", error="failed",
        ))

        router = SignalRouter(
            DiscordConfig(channel_ids=["ch1"]),
            mock_api,
            ai_parser=None,
            signal_queue=None,
        )

        # 不應報錯
        await router._forward_signal("BTC SHORT 68000")


# ==================== Replay 邏輯 ====================

class TestReplayQueue:
    """main._replay_queue 的測試。"""

    @pytest.mark.asyncio
    async def test_replay_success_dequeues(self, queue):
        """重播成功 → dequeue 移除。"""
        from src.main import _replay_queue
        from src.api_client import ExecutionResult

        queue.enqueue("send_trade", _make_payload(), _make_source())

        mock_api = AsyncMock()
        mock_api.send_trade = AsyncMock(return_value=ExecutionResult(
            success=True, status_code=200, summary="ok",
        ))

        await _replay_queue(mock_api, queue)
        assert queue.size() == 0

    @pytest.mark.asyncio
    async def test_replay_4xx_removes_invalid(self, queue):
        """重播 4xx → 移除（訊號有問題）。"""
        from src.main import _replay_queue
        from src.api_client import ExecutionResult

        queue.enqueue("send_trade", _make_payload(), _make_source())

        mock_api = AsyncMock()
        mock_api.send_trade = AsyncMock(return_value=ExecutionResult(
            success=False, status_code=400, summary="", error="bad request",
        ))

        await _replay_queue(mock_api, queue)
        assert queue.size() == 0  # 被移除

    @pytest.mark.asyncio
    async def test_replay_5xx_stops_and_increments(self, queue):
        """重播 5xx → increment_attempt + 停止。"""
        from src.main import _replay_queue
        from src.api_client import ExecutionResult

        queue.enqueue("send_trade", _make_payload(), _make_source("m1"))
        queue.enqueue("send_trade", _make_payload(), _make_source("m2"))

        mock_api = AsyncMock()
        mock_api.send_trade = AsyncMock(return_value=ExecutionResult(
            success=False, status_code=0, summary="", error="connection refused",
        ))

        await _replay_queue(mock_api, queue)

        entries = queue.peek_all()
        assert len(entries) == 2  # 都還在（第一筆失敗後停止）
        assert entries[0]["attempt_count"] == 1  # 第一筆被 increment
        assert entries[1]["attempt_count"] == 0  # 第二筆沒被處理

    @pytest.mark.asyncio
    async def test_replay_max_attempts_removes(self, queue):
        """超過 max_replay_attempts → 移除。"""
        from src.main import _replay_queue
        from src.api_client import ExecutionResult

        queue.enqueue("send_trade", _make_payload(), _make_source())

        # 手動設定 attempt_count 到上限
        entries = queue.peek_all()
        entries[0]["attempt_count"] = 5  # = max_replay_attempts
        queue._save(entries)

        mock_api = AsyncMock()
        await _replay_queue(mock_api, queue)

        assert queue.size() == 0  # 被移除
        mock_api.send_trade.assert_not_called()  # 沒有嘗試發送

    @pytest.mark.asyncio
    async def test_replay_send_signal_type(self, queue):
        """send_signal 類型的重播。"""
        from src.main import _replay_queue
        from src.api_client import ExecutionResult

        queue.enqueue("send_signal", {"message": "BTC SHORT 68000"}, _make_source())

        mock_api = AsyncMock()
        mock_api.send_signal = AsyncMock(return_value=ExecutionResult(
            success=True, status_code=200, summary="ok",
        ))

        await _replay_queue(mock_api, queue)
        assert queue.size() == 0
        mock_api.send_signal.assert_called_once()

    @pytest.mark.asyncio
    async def test_replay_empty_queue_noop(self, queue):
        """空佇列 → 不呼叫 API。"""
        from src.main import _replay_queue

        mock_api = AsyncMock()
        await _replay_queue(mock_api, queue)

        mock_api.send_trade.assert_not_called()
        mock_api.send_signal.assert_not_called()

    @pytest.mark.asyncio
    async def test_replay_mixed_results(self, queue):
        """混合結果：第一筆成功、第二筆失敗 → 停在第二筆。"""
        from src.main import _replay_queue
        from src.api_client import ExecutionResult

        queue.enqueue("send_trade", _make_payload("ENTRY"), _make_source("m1"))
        queue.enqueue("send_trade", _make_payload("CLOSE"), _make_source("m2"))

        mock_api = AsyncMock()
        mock_api.send_trade = AsyncMock(side_effect=[
            ExecutionResult(success=True, status_code=200, summary="ok"),
            ExecutionResult(success=False, status_code=0, summary="", error="timeout"),
        ])

        await _replay_queue(mock_api, queue)

        entries = queue.peek_all()
        assert len(entries) == 1  # 第一筆被移除，第二筆還在
        assert entries[0]["source"]["message_id"] == "m2"
        assert entries[0]["attempt_count"] == 1
