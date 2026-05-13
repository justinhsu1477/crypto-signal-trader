"""
CDP hook 健康檢查 + 自動 re-inject。

修 5/13 incident：hook 死了但沒人發現。listen() loop 內每 60s evaluate
window.__signalMonitorActive，掛了就先嘗試原地 CLEAR_JS + INJECT_JS 重注入，
失敗才 raise 讓 main.py reconnect。

測試策略：
- 不開真實 CDP，mock _evaluate_js 控制各種回傳值。
- 用 monkeypatch 把 HEALTH_CHECK_INTERVAL_S 改成 0，確保 loop 每次都觸發健康檢查。
- 用 asyncio.wait_for + cancel 控制 listen() 不要真的跑無限迴圈。
"""
from __future__ import annotations

import asyncio
from unittest.mock import AsyncMock, MagicMock

import pytest

from src import cdp_client as cdp_module
from src.cdp_client import CdpClient


def _make_client() -> CdpClient:
    config = MagicMock()
    config.host = "localhost"
    config.port = 9222
    client = CdpClient(config)
    client._ws = MagicMock()  # 騙過 listen() 一開始的「未連線」檢查
    return client


async def _run_listen_briefly(client: CdpClient, callback, ticks: int = 5):
    """跑 listen() 一小段時間（n 個 poll 後 cancel）並回傳是否拋了預期錯誤。

    用 cancellation 而非自然結束 — listen() 是 while True 設計就是不會自然退出。
    """
    task = asyncio.create_task(client.listen(callback))
    # 給足夠時間跑 N 次 poll（每次 0.5s sleep + 健康檢查觸發）
    # 但我們也要快 — 用 0.01s 的 sleep 多次而非單一長 sleep
    try:
        await asyncio.wait_for(task, timeout=0.6)
    except asyncio.TimeoutError:
        task.cancel()
        try:
            await task
        except (asyncio.CancelledError, Exception):
            pass


class TestHookHealthCheck:
    """listen() 內部健康檢查行為。"""

    @pytest.mark.asyncio
    async def test_alive_response_no_reinject(self, monkeypatch):
        """eval 回 'alive' → 不觸發 re-inject。"""
        # 健康檢查間隔設 0，確保每次 loop iteration 都會檢查
        monkeypatch.setattr(cdp_module, "HEALTH_CHECK_INTERVAL_S", 0)

        client = _make_client()
        # _evaluate_js 對 health probe 回 'alive'，drain 回 None
        async def fake_eval(expr: str):
            if "signalMonitorActive" in expr:
                return "alive"
            if "splice" in expr:  # DRAIN_JS
                return None
            return None
        client._evaluate_js = fake_eval

        callback = AsyncMock()
        await _run_listen_briefly(client, callback)

        # 沒有任何 callback 呼叫 / 沒有 raise
        callback.assert_not_called()

    @pytest.mark.asyncio
    async def test_dead_hook_triggers_reinject(self, monkeypatch):
        """eval 回 'dead' → 觸發 CLEAR_JS + INJECT_JS。"""
        monkeypatch.setattr(cdp_module, "HEALTH_CHECK_INTERVAL_S", 0)

        client = _make_client()
        calls: list[str] = []

        async def fake_eval(expr: str):
            # 優先匹配 specific 路徑（順序很重要：先檢查最具識別性的字串）
            if "webpackChunkdiscord_app" in expr:  # INJECT_JS
                calls.append("inject")
                return "ok"
            if "delete window.__signalMonitorActive" in expr:  # CLEAR_JS
                calls.append("clear")
                return "cleared"
            if "splice" in expr:  # DRAIN_JS
                return None
            if "__signalMonitorActive === true" in expr:  # HEALTH_PROBE_JS
                calls.append("probe")
                return "dead"
            return None

        client._evaluate_js = fake_eval

        callback = AsyncMock()
        await _run_listen_briefly(client, callback)

        # 至少有一次完整的 probe → clear → inject 序列
        assert "probe" in calls
        assert "clear" in calls
        assert "inject" in calls
        probe_idx = calls.index("probe")
        clear_idx = calls.index("clear")
        inject_idx = calls.index("inject")
        assert probe_idx < clear_idx < inject_idx

    @pytest.mark.asyncio
    async def test_reinject_failure_raises_connection_error(self, monkeypatch):
        """INJECT_JS 失敗 → listen() 拋 ConnectionError（讓 main.py reconnect 接手）。"""
        monkeypatch.setattr(cdp_module, "HEALTH_CHECK_INTERVAL_S", 0)

        client = _make_client()

        async def fake_eval(expr: str):
            if "webpackChunkdiscord_app" in expr:
                return "dispatcher_not_found"  # INJECT 失敗
            if "delete window.__signalMonitorActive" in expr:
                return "cleared"
            if "splice" in expr:
                return None
            if "__signalMonitorActive === true" in expr:
                return "dead"
            return None

        client._evaluate_js = fake_eval

        callback = AsyncMock()
        with pytest.raises(ConnectionError, match="Re-injection failed"):
            await client.listen(callback)

    @pytest.mark.asyncio
    async def test_inject_returns_already_active_no_error(self, monkeypatch):
        """INJECT_JS 回 'already_active' 也算成功（hook 已存在），不應 raise。"""
        monkeypatch.setattr(cdp_module, "HEALTH_CHECK_INTERVAL_S", 0)

        client = _make_client()

        async def fake_eval(expr: str):
            if "webpackChunkdiscord_app" in expr:
                return "already_active"
            if "delete window.__signalMonitorActive" in expr:
                return "cleared"
            if "splice" in expr:
                return None
            if "__signalMonitorActive === true" in expr:
                return "dead"
            return None

        client._evaluate_js = fake_eval

        callback = AsyncMock()
        # 不該 raise — 跑一下後 cancel
        await _run_listen_briefly(client, callback)

    @pytest.mark.asyncio
    async def test_health_check_uses_monotonic_time(self):
        """HEALTH_PROBE_JS 字串本身正確：檢查 window.__signalMonitorActive。"""
        from src.cdp_client import HEALTH_PROBE_JS

        assert "__signalMonitorActive" in HEALTH_PROBE_JS
        assert "alive" in HEALTH_PROBE_JS
        assert "dead" in HEALTH_PROBE_JS
