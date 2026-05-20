"""Monitor version detection — 啟動時讀本機 git HEAD 當識別碼用。

用途：Python heartbeat 帶上自己跑的 commit，Java 端可比對 main HEAD 看
本地 Python 是不是落後（5/13 silent capture failure 那種狀況的 visibility）。

設計：
- subprocess 失敗或不在 git tree → "unknown"（永不丟例外）
- 只讀一次（main.py 啟動時呼叫），不每次 heartbeat 重讀
- 用 7 字 prefix（跟 git log --oneline 一致，足以對齊 commit）
"""
from __future__ import annotations

import logging
import subprocess
from pathlib import Path

logger = logging.getLogger(__name__)

_TIMEOUT_SECONDS = 2.0


def get_monitor_version() -> str:
    """讀 git HEAD 前 7 字。失敗回 "unknown"，永不拋例外。"""
    try:
        # 從 src/version.py 出發找 repo root（src/../../ = discord-monitor/）
        repo_root = Path(__file__).resolve().parent.parent
        result = subprocess.run(
            ["git", "-C", str(repo_root), "rev-parse", "--short=7", "HEAD"],
            capture_output=True,
            text=True,
            timeout=_TIMEOUT_SECONDS,
            check=True,
        )
        version = result.stdout.strip()
        if not version:
            return "unknown"
        return version
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired,
            FileNotFoundError, OSError) as e:
        logger.debug("monitor version detection failed: %s", e)
        return "unknown"
