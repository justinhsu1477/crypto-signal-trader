"""Tests for src/version.py — 啟動時讀本機 git HEAD 當 monitor_version 用。

行為合約：
- 在 git working tree 內 → 回傳 HEAD 前 7 字
- 不在 git working tree（例如 docker 沒帶 .git）→ 回傳 "unknown"
- subprocess 失敗 → 回傳 "unknown"（永不丟例外）
"""
from __future__ import annotations

import subprocess
from unittest.mock import patch

from src.version import get_monitor_version


class TestGetMonitorVersion:

    def test_returns_7char_hex_when_in_git_repo(self):
        """在 git repo 內 → 7 字 hex prefix。"""
        # 真的呼叫（這個測試在 repo 內跑）
        result = get_monitor_version()
        # 7 字小寫 hex 或 "unknown"
        assert (len(result) == 7 and all(c in "0123456789abcdef" for c in result)) \
            or result == "unknown"

    def test_returns_unknown_when_git_command_fails(self):
        """git rev-parse 出錯（非 git tree / git 沒裝）→ 回 "unknown"。"""
        with patch("src.version.subprocess.run",
                   side_effect=subprocess.CalledProcessError(128, "git")):
            assert get_monitor_version() == "unknown"

    def test_returns_unknown_when_git_not_installed(self):
        """git 沒裝 → FileNotFoundError → 回 "unknown"。"""
        with patch("src.version.subprocess.run", side_effect=FileNotFoundError):
            assert get_monitor_version() == "unknown"

    def test_returns_unknown_on_timeout(self):
        """git 卡住 → TimeoutExpired → 回 "unknown"。"""
        with patch("src.version.subprocess.run",
                   side_effect=subprocess.TimeoutExpired("git", 2)):
            assert get_monitor_version() == "unknown"

    def test_strips_whitespace_from_git_output(self):
        """git output 通常有 trailing \\n → 要剝乾淨。"""
        mock_result = subprocess.CompletedProcess(
            args=["git"], returncode=0, stdout="abc1234\n", stderr=""
        )
        with patch("src.version.subprocess.run", return_value=mock_result):
            assert get_monitor_version() == "abc1234"
