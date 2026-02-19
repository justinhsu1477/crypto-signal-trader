"""
單元測試 — TradeActionDetector

測試覆蓋：
1. CLOSE 動作偵測 ✅
2. 矛盾檢驗 ✅
3. 部分平倉百分比提取（預留）
4. AI 結果微調
"""

import pytest
from src.trade_action_detector import TradeActionDetector


class TestTradeActionDetectorClose:
    """測試完全平倉偵測"""

    def setup_method(self):
        self.detector = TradeActionDetector()

    def test_close_keyword_取出局(self):
        """測試『止盈出局』被正確識別為平倉"""
        message = "短线收益止盈出局【收益800点】"
        assert self.detector.detect_close(message) is True

    def test_close_keyword_出局(self):
        """測試『出局』被識別為平倉"""
        message = "BTC 出局 盈利 1000 點"
        assert self.detector.detect_close(message) is True

    def test_close_keyword_全部平倉(self):
        """測試『全部平倉』"""
        message = "全部平倉"
        assert self.detector.detect_close(message) is True

    def test_close_keyword_平倉(self):
        """測試『平倉』"""
        message = "現價平倉 BTC"
        assert self.detector.detect_close(message) is True

    def test_close_keyword_平仓(self):
        """測試簡體『平仓』"""
        message = "市价平仓"
        assert self.detector.detect_close(message) is True

    def test_close_keyword_清倉(self):
        """測試『清倉』"""
        message = "清倉所有持倉"
        assert self.detector.detect_close(message) is True

    def test_no_close_keyword(self):
        """測試不包含平倉關鍵詞時回傳 False"""
        message = "BTC 現在 67200，可以考慮進場做多"
        assert self.detector.detect_close(message) is False

    def test_empty_message(self):
        """測試空訊息"""
        assert self.detector.detect_close("") is False
        assert self.detector.detect_close(None) is False

    def test_close_with_holding_keyword_returns_false(self):
        """
        ⚠️ 重要：測試『止盈50%做成本保護繼續持有』

        邏輯：雖然有『平倉』關鍵詞，但同時有『繼續持有』
        當前版本：視為 INFO（讓 AI Parser 處理）

        這是陳哥的特殊說法，需要 AI 來判斷是部分平倉還是單純移動止損
        """
        message = "中长线止盈50%做成本保护继续持有"
        # 會有「止盈」關鍵詞但沒有「平倉」關鍵詞，所以回傳 False
        assert self.detector.detect_close(message) is False

    def test_close_without_holding_is_true(self):
        """測試『止盈出局』（無『繼續持有』）應回傳 True"""
        message = "短线收益止盈出局【收益800点】"
        # 有「出局」，沒有「繼續持有」
        assert self.detector.detect_close(message) is True

    def test_partial_close_not_detected_by_close(self):
        """
        測試『止盈50%』不被視為完全平倉

        邏輯：目前只有「止盈50%」，沒有「平倉」或「出局」關鍵詞
        回傳 False（因為不在 close_keywords 中）
        """
        message = "止盈50%"
        # 「止盈」不在 close_keywords，所以回傳 False
        assert self.detector.detect_close(message) is False


class TestTradeActionDetectorValidation:
    """測試驗證函數"""

    def setup_method(self):
        self.detector = TradeActionDetector()

    def test_validate_close_without_holding_is_true(self):
        """測試合理的 CLOSE（無『繼續持有』）"""
        message = "止盈出局"
        assert self.detector.validate('CLOSE', message) is True

    def test_validate_close_with_holding_is_false(self):
        """測試矛盾的 CLOSE（同時有『繼續持有』）"""
        message = "止盈出局但繼續持有"
        assert self.detector.validate('CLOSE', message) is False

    def test_validate_other_actions_always_true(self):
        """測試其他動作驗證（當前版本總是回傳 True）"""
        assert self.detector.validate('ENTRY', "any message") is True
        assert self.detector.validate('INFO', "any message") is True


class TestTradeActionDetectorPartialClose:
    """測試部分平倉百分比提取（預留功能）"""

    def setup_method(self):
        self.detector = TradeActionDetector()

    def test_extract_50_percent(self):
        """測試提取 50%"""
        message = "止盈50%"
        percentage = self.detector.detect_partial_close_percentage(message)
        assert percentage == 0.5

    def test_extract_100_percent(self):
        """測試提取 100%"""
        message = "平100%"
        percentage = self.detector.detect_partial_close_percentage(message)
        assert percentage == 1.0

    def test_extract_no_percentage(self):
        """測試無百分比"""
        message = "止盈出局"
        percentage = self.detector.detect_partial_close_percentage(message)
        assert percentage is None


class TestTradeActionDetectorRefinement:
    """測試 AI 結果微調"""

    def setup_method(self):
        self.detector = TradeActionDetector()

    def test_refine_info_to_close(self):
        """測試 INFO → CLOSE 的微調"""
        ai_result = {
            'action': 'INFO',
            'symbol': 'BTCUSDT',
        }
        raw_message = "短线收益止盈出局【收益800点】"

        refined = self.detector.refine_ai_result(ai_result, raw_message)

        # 應該改為 CLOSE
        assert refined['action'] == 'CLOSE'
        # 應該記錄微調資訊
        assert refined['_detector_refinement'] == 'INFO→CLOSE by TradeActionDetector'

    def test_refine_entry_unchanged(self):
        """測試 ENTRY 不會被改變"""
        ai_result = {
            'action': 'ENTRY',
            'symbol': 'BTCUSDT',
            'side': 'LONG',
            'entry_price': 67200,
        }
        raw_message = "BTC 67200 附近做多"

        refined = self.detector.refine_ai_result(ai_result, raw_message)

        # ENTRY 應該保持不變
        assert refined['action'] == 'ENTRY'
        assert '_detector_refinement' not in refined

    def test_refine_close_unchanged(self):
        """測試 CLOSE 已經是 CLOSE，不改變"""
        ai_result = {
            'action': 'CLOSE',
            'symbol': 'BTCUSDT',
        }
        raw_message = "止盈出局"

        refined = self.detector.refine_ai_result(ai_result, raw_message)

        # 已經是 CLOSE，不改變
        assert refined['action'] == 'CLOSE'
        assert '_detector_refinement' not in refined


# ========== 集成測試：真實場景 ==========

class TestRealWorldScenarios:
    """測試真實的陳哥訊息"""

    def setup_method(self):
        self.detector = TradeActionDetector()

    def test_陈哥短线止盈出局(self):
        """陳哥的短線止盈訊息"""
        message = "短线收益止盈出局【收益800点】"
        assert self.detector.detect_close(message) is True

    def test_陈哥中长线止盈50做成本保护继续持有(self):
        """陳哥的中長線部分止盈訊息（目前無法判別，應由 AI 處理）"""
        message = "中长线止盈50%做成本保护继续持有"
        # 無「平倉」關鍵詞，所以回傳 False
        assert self.detector.detect_close(message) is False

    def test_完全平仓(self):
        """完全平倉訊息"""
        message = "🎉🎉🎉 完全平仓 【收益500点】"
        assert self.detector.detect_close(message) is True

    def test_平倉但繼續持有矛盾(self):
        """矛盾訊息：同時說平倉和繼續持有"""
        message = "平倉50%但繼續持有剩餘倉位"
        # 有「平倉」但也有「持有」
        # 當前邏輯：檢測到矛盾，回傳 False
        # 應由更聰明的 AI 來判別
        result = self.detector.detect_close(message)
        # 此訊息有「平倉」但有「持有」，視為矛盾
        # 實際結果取決於具體實現
        # 讓我驗證：有「平倉」和「持有」
        has_close = any(kw in message for kw in self.detector.close_keywords)
        has_holding = any(kw in message for kw in self.detector.holding_keywords)
        # 有「平倉」（True）但有「繼續持有」（False - 因為是「持有」不是「繼續持有」）
        # 實際上「持有」不在 holding_keywords 中，所以應回傳 True
        # 讓我檢查關鍵詞...
        # holding_keywords: '繼續持有', '继续持有', '繼續', '继续', '做成本保護繼續持有', ...
        # 訊息中有「繼續持有」，所以 has_holding = True
        # 因此應回傳 False（有矛盾）
        assert result is False


if __name__ == '__main__':
    pytest.main([__file__, '-v'])
