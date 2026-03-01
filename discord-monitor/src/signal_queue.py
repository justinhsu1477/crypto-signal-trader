"""Failed signal queue — 本地持久化佇列，API 當機時暫存訊號，恢復後自動重播。

架構：
  signal_router._forward_signal()
      │
      ├─ API 成功 → 正常處理
      └─ API 失敗 (status_code=0, 全部 retry 失敗)
           → SignalQueue.enqueue() → data/signal_queue.json

  heartbeat_loop (每 30 秒)
      │
      └─ heartbeat 成功 + queue 有資料
           → replay_queue() → 依序重播 → 成功移除 / 失敗停止等下一輪

去重保護鏈：
  1. Server 5分鐘 hash 去重（ConcurrentHashMap + DB）
  2. Server message_id 永久去重（signals 表 sourceMessageId 欄位）
  3. 本地 queue max_age_hours 過期清除（預設 24h）
"""
from __future__ import annotations

import json
import logging
import os
import secrets
import time
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

logger = logging.getLogger(__name__)

TZ = ZoneInfo("Asia/Taipei")
QUEUE_FILENAME = "signal_queue.json"


@dataclass
class QueueConfig:
    """佇列設定"""
    enabled: bool = True
    queue_dir: str = "data"
    max_size: int = 100
    max_age_hours: int = 24
    max_replay_attempts: int = 5


class SignalQueue:
    """File-based signal queue with atomic write and corruption resilience.

    設計重點：
    - 原子寫入：先寫 .tmp 再 os.replace()，防斷電損毀
    - 損毀容錯：JSON 解析失敗 → 回傳空 list（不 crash）
    - 自動過期：超過 max_age_hours 的訊號自動清除
    - 上限控制：超過 max_size 拒絕存入（log 警告）
    - 單執行緒安全：asyncio 環境下無需 lock
    """

    def __init__(self, config: QueueConfig):
        self.config = config
        self._queue_dir = Path(config.queue_dir)
        self._queue_file = self._queue_dir / QUEUE_FILENAME
        self._tmp_file = self._queue_dir / f"{QUEUE_FILENAME}.tmp"

        # 確保目錄存在
        os.makedirs(self._queue_dir, exist_ok=True)

    def enqueue(
        self,
        call_type: str,
        payload: dict,
        source: dict | None,
        dry_run: bool = False,
        original_content: str = "",
    ) -> bool:
        """API 失敗時存入佇列。

        Args:
            call_type: "send_trade" (AI 解析結構化) 或 "send_signal" (regex fallback)
            payload: send_trade → trade request dict; send_signal → {"message": "raw text"}
            source: 訊號來源 metadata（platform, channel_id, message_id 等）
            dry_run: 是否為 dry-run 模式
            original_content: 原始 Discord 訊息文字（for logging）

        Returns:
            True = 已存入, False = 佇列已滿
        """
        entries = self._load()

        if len(entries) >= self.config.max_size:
            logger.warning(
                "⚠️ 訊號佇列已滿 (%d/%d)，訊號被丟棄: %s %s",
                len(entries), self.config.max_size,
                payload.get("action", ""),
                payload.get("symbol", original_content[:60]),
            )
            return False

        entry_id = self._generate_id()
        entry = {
            "id": entry_id,
            "queued_at": datetime.now(TZ).isoformat(),
            "call_type": call_type,
            "payload": payload,
            "source": source,
            "dry_run": dry_run,
            "attempt_count": 0,
            "last_attempt_at": None,
            "original_content": original_content[:500],  # 截斷，只用於 debug
        }

        entries.append(entry)
        self._save(entries)

        logger.info(
            "📥 訊號已存入佇列: id=%s type=%s action=%s symbol=%s (佇列深度: %d)",
            entry_id, call_type,
            payload.get("action", "?"),
            payload.get("symbol", "?"),
            len(entries),
        )
        return True

    def dequeue(self, entry_id: str) -> None:
        """重播成功後移除指定項目。"""
        entries = self._load()
        original_len = len(entries)
        entries = [e for e in entries if e.get("id") != entry_id]

        if len(entries) < original_len:
            self._save(entries)
            logger.debug("佇列移除: id=%s (剩餘: %d)", entry_id, len(entries))

    def peek_all(self) -> list[dict]:
        """讀取所有待重播項目（自動清除過期），FIFO 順序（最舊的先）。"""
        entries = self._load()
        pruned = self._prune_expired(entries)

        if len(pruned) < len(entries):
            removed = len(entries) - len(pruned)
            logger.info("🗑️ 清除 %d 筆過期佇列訊號（超過 %dh）", removed, self.config.max_age_hours)
            self._save(pruned)

        return pruned

    def increment_attempt(self, entry_id: str) -> None:
        """重播失敗時累加計數和時間戳。"""
        entries = self._load()
        for e in entries:
            if e.get("id") == entry_id:
                e["attempt_count"] = e.get("attempt_count", 0) + 1
                e["last_attempt_at"] = datetime.now(TZ).isoformat()
                break
        self._save(entries)

    def size(self) -> int:
        """目前佇列深度。"""
        return len(self._load())

    # ==================== Internal ====================

    def _load(self) -> list[dict]:
        """從磁碟讀取佇列。檔案不存在或損毀 → 回傳空 list。"""
        if not self._queue_file.exists():
            return []

        try:
            with open(self._queue_file, "r", encoding="utf-8") as f:
                data = json.load(f)
            if isinstance(data, list):
                return data
            logger.warning("佇列檔案格式錯誤（非 array），已忽略")
            return []
        except json.JSONDecodeError:
            logger.warning("佇列檔案 JSON 損毀，已忽略（佇列重置為空）")
            return []
        except Exception as e:
            logger.error("讀取佇列檔案失敗: %s", e)
            return []

    def _save(self, entries: list[dict]) -> None:
        """原子寫入：先寫 .tmp 再 os.replace()，防斷電損毀。"""
        try:
            with open(self._tmp_file, "w", encoding="utf-8") as f:
                json.dump(entries, f, ensure_ascii=False, indent=2)
            os.replace(self._tmp_file, self._queue_file)
        except Exception as e:
            logger.error("寫入佇列檔案失敗: %s", e)
            # 清理 tmp 檔案
            try:
                self._tmp_file.unlink(missing_ok=True)
            except Exception:
                pass

    def _prune_expired(self, entries: list[dict]) -> list[dict]:
        """移除超過 max_age_hours 的過期項目。"""
        cutoff = datetime.now(TZ) - timedelta(hours=self.config.max_age_hours)
        result = []
        for e in entries:
            try:
                queued_at = datetime.fromisoformat(e["queued_at"])
                if queued_at >= cutoff:
                    result.append(e)
            except (KeyError, ValueError):
                # 缺少或無效的 queued_at → 保留（不冒險刪除）
                result.append(e)
        return result

    @staticmethod
    def _generate_id() -> str:
        """生成唯一 ID: sq_{unix_ts}_{random_hex6}"""
        return f"sq_{int(time.time())}_{secrets.token_hex(3)}"
