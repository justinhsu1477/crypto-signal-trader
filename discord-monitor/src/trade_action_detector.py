"""
Trade Action Detector — 偵測交易動作（補充 AI Parser 無法判別的口語化表達）

PURPOSE:
--------
補充 AI Parser（Gemini）的不足，針對陳哥等交易員的口語化說法進行判斷。
例：「止盈出局」「做成本保護繼續持有」等 AI 無法準確判別的表述。

USAGE:
------
1. 作為 AI Parser 的後處理器（post-processor）
2. 當 AI Parser 無法確定是否平倉時，用此檢測器輔助判斷
3. 記錄在 MD 文件中，供未來開發者參考

ARCHITECTURE:
------
SignalRouter
    ↓
ai_parser.parse()  ← AI 解析（主要）
    ↓
TradeActionDetector.refine()  ← 額外判斷（補充）
    ↓
execute_trade()

RULES:
------
目前支援的判斷：
1. CLOSE (完全平倉)
   - 關鍵詞：「止盈出局」「出局」「全部平倉」「平倉」

待擴展的判斷：
2. PARTIAL_CLOSE (部分平倉) - 暫不實施，先完成 CLOSE
3. DCA (加倉) - 暫不實施

⚠️ IMPORTANT:
- 此檢測器的判斷優先級低於 AI Parser
- 只在 AI Parser 結果為 UNKNOWN 或 INFO 時才使用
- 不會改變現有的 ENTRY/CANCEL/MOVE_SL 判斷
"""

import logging
import re
from typing import Optional, Dict, Any

logger = logging.getLogger(__name__)


class TradeActionDetector:
    """
    交易動作檢測器 — 偵測口語化的交易表述

    當前版本：只支援 CLOSE 動作檢測

    Design Notes:
    - 集中管理關鍵詞，便於擴展
    - 提供驗證函數，檢查邏輯矛盾
    - 非同步友善（同步函數，可在 async 中調用）
    """

    def __init__(self):
        """初始化關鍵詞清單"""

        # CLOSE - 完全平倉
        self.close_keywords = [
            '止盈出局',      # 短線止盈出局
            '出局',          # 通用出局
            '全部平倉',      # 全部平倉
            '全部平仓',      # 簡體
            '平倉',          # 通用平倉
            '平仓',          # 簡體
            '清倉',          # 清倉
            '清仓',          # 簡體
        ]

        # HOLD - 繼續持有（用於檢測矛盾）
        self.holding_keywords = [
            '繼續持有',
            '继续持有',
            '繼續',
            '继续',
            '做成本保護繼續持有',
            '做成本保护继续持有',
        ]

        # PARTIAL_CLOSE - 部分平倉（暫不使用，為未來擴展預留）
        self.partial_close_keywords = [
            '止盈50%',
            '平50%',
            '減倉',
            '减仓',
            '做成本保護',     # 暫視為 INFO，不判為平倉
            '做成本保护',
        ]

        # DCA - 加倉（暫不使用，為未來擴展預留）
        self.add_keywords = [
            '加倉',
            '加仓',
            '補倉',
            '补仓',
            '追加',
            '掛單',
        ]

    def detect_close(self, message: str) -> bool:
        """
        偵測訊息是否表示完全平倉

        Args:
            message: Discord 訊息內容

        Returns:
            True 如果檢測到完全平倉指令，False 否則

        Logic:
            1. 檢查是否包含平倉關鍵詞
            2. 檢查是否同時包含「繼續持有」（矛盾，不算平倉）
            3. 返回最終判斷
        """
        if not message:
            return False

        # 檢查平倉關鍵詞
        has_close_keyword = self._contains_any(message, self.close_keywords)
        if not has_close_keyword:
            return False

        # 檢查是否同時有「繼續持有」（矛盾情況）
        has_holding_keyword = self._contains_any(message, self.holding_keywords)

        # 如果有「止盈出局」或「出局」或「平倉」，即使有「繼續持有」也視為平倉
        # 因為「做成本保護繼續持有」是特殊case，應由 AI Parser 處理
        if has_holding_keyword:
            logger.warning(f"訊息同時包含平倉和持有關鍵詞（矛盾）: {message[:100]}")
            # 暫時視為 INFO，不判為平倉
            # 理由：AI Parser 應該能判別這種複雜情況
            return False

        return True

    def detect_partial_close_percentage(self, message: str) -> Optional[float]:
        """
        偵測部分平倉的百分比

        ⚠️ 當前未使用，為未來擴展預留

        Args:
            message: Discord 訊息內容

        Returns:
            平倉百分比（0-1），或 None 如果無法偵測
        """
        # 檢查「止盈50%」「平50%」等
        match = re.search(r'(?:止盈|平)(\d+)%', message)
        if match:
            percentage = int(match.group(1))
            if 0 < percentage <= 100:
                return percentage / 100.0
        return None

    def validate(self, action: str, message: str) -> bool:
        """
        驗證檢測到的動作是否合邏輯

        Args:
            action: 檢測到的動作 ('CLOSE', 'PARTIAL_CLOSE', etc.)
            message: 原始訊息

        Returns:
            True 如果動作合理，False 如果有矛盾
        """
        if action == 'CLOSE':
            # 檢查是否同時有矛盾的「繼續持有」
            has_holding = self._contains_any(message, self.holding_keywords)
            if has_holding:
                logger.warning(f"邏輯矛盾：CLOSE 但 'Continuing to hold': {message[:100]}")
                return False

        return True

    def _contains_any(self, text: str, keywords: list) -> bool:
        """檢查文本是否包含任何關鍵詞"""
        if not text or not keywords:
            return False
        return any(kw in text for kw in keywords)

    # ========== 用於 AI Parser 後處理的輔助方法 ==========

    def refine_ai_result(self, ai_result: Dict[str, Any], raw_message: str) -> Dict[str, Any]:
        """
        基於原始訊息對 AI 結果進行微調

        ⚠️ 當前版本：只在 AI 結果無法確定時使用此檢測器

        Args:
            ai_result: AI Parser 的輸出 (dict with 'action', 'symbol', etc.)
            raw_message: 原始 Discord 訊息

        Returns:
            可能修改的 ai_result

        邏輯：
        1. 如果 AI 判為 INFO 但檢測器偵測到完全平倉 → 改為 CLOSE
        2. 否則保持 AI 結果
        3. 記錄所有修改供審計
        """
        original_action = ai_result.get('action')

        # 只在 AI 無法確定的情況下才改寫
        if original_action == 'INFO' and self.detect_close(raw_message):
            logger.info(f"TradeActionDetector 補救：INFO → CLOSE (message: {raw_message[:100]})")
            ai_result['action'] = 'CLOSE'
            # 記錄此修改，供日後審計
            ai_result['_detector_refinement'] = 'INFO→CLOSE by TradeActionDetector'

        return ai_result


# ========== 全局實例 ==========
detector = TradeActionDetector()
