# TradeActionDetector — 交易動作補充檢測器

## 📋 概述

**目的**：補充 AI Parser（Gemini）無法判別的口語化交易表述。

**背景**：陳哥等交易員使用許多 AI 難以理解的口語化說法：
- 「短線止盈出局」← 這是完全平倉
- 「中長線止盈50%做成本保護繼續持有」← 這是部分平倉 + 止損移動

AI Parser 有時無法準確判斷，所以需要額外的關鍵詞匹配作為補助。

---

## 🎯 當前支援的功能

### ✅ 已實施

**CLOSE（完全平倉）偵測**
```python
detector = TradeActionDetector()
detector.detect_close("短线收益止盈出局【收益800点】")  # → True
detector.detect_close("出局")                         # → True
detector.detect_close("全部平倉")                     # → True
```

**關鍵詞清單**（位於 `src/trade_action_detector.py` 第 54-68 行）：
```python
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
```

### ⏳ 暫未實施（預留接口）

**PARTIAL_CLOSE（部分平倉）**
- 目標：判別「止盈50%」「平50%」等
- 原因：「止盈50%做成本保護繼續持有」需要 AI 判斷複雜性，還未實施
- 計劃：2025年Q2實施

**DCA（加倉）**
- 目標：判別「加倉」「補倉」等
- 原因：目前 AI Parser 已支援，暫無額外需求
- 計劃：如 AI 無法判別時實施

---

## 🏗️ 架構

### 集成位置

```
SignalRouter.handle_message(msg)
    ↓
ai_parser.parse(content)  ← AI 解析（主要）
    ↓
    IF ai_result.action == 'INFO' or other uncertain cases:
        TradeActionDetector.refine_ai_result(ai_result, raw_message)
            ↓
            IF detect_close(message) → action = 'CLOSE'
    ↓
execute_trade(refined_result)
```

### 優先級

```
優先級 1（最高）: AI Parser (Gemini)
優先級 2（補助）: TradeActionDetector (關鍵詞匹配)
```

**原則**：
- AI Parser 結果正確時，不覆蓋
- 只在 AI Parser 無法確定（返回 INFO）時才補助

---

## 💻 使用方式

### 1. 在 Signal Router 中使用（推薦）

```python
# src/signal_router.py

from trade_action_detector import detector

async def handle_message(self, msg: dict):
    content = self._build_content(msg)

    # AI 解析
    parsed = await self.ai_parser.parse(content)

    # TradeActionDetector 補助判斷
    parsed = detector.refine_ai_result(parsed, content)

    # 轉發給後端
    await self._forward_signal(parsed)
```

### 2. 直接使用檢測器

```python
from trade_action_detector import detector

message = "短线收益止盈出局【收益800点】"

# 檢測是否平倉
if detector.detect_close(message):
    print("檢測到完全平倉")

# 驗證邏輯是否矛盾
if detector.validate('CLOSE', message):
    print("邏輯合理")
else:
    print("邏輯矛盾，需要人工檢查")

# 修改 AI 結果
ai_result = {'action': 'INFO', 'symbol': 'BTCUSDT'}
refined = detector.refine_ai_result(ai_result, message)
# refined['action'] 可能被改為 'CLOSE'
```

---

## 🧪 測試

### 執行測試

```bash
cd discord-monitor
python3 -m pytest tests/test_trade_action_detector.py -v
```

### 測試覆蓋

- ✅ 24 個單元測試
- ✅ CLOSE 關鍵詞偵測
- ✅ 邏輯矛盾驗證
- ✅ 真實場景（陳哥訊息）

### 範例測試

```python
def test_陈哥短线止盈出局(self):
    message = "短线收益止盈出局【收益800点】"
    assert detector.detect_close(message) is True

def test_陈哥中长线止盈50做成本保护继续持有(self):
    message = "中长线止盈50%做成本保护继续持有"
    # 無「平倉」關鍵詞，回傳 False（讓 AI 處理）
    assert detector.detect_close(message) is False
```

---

## 📝 擴展指南（未來開發）

### 如何添加新的關鍵詞？

**場景**：陳哥使用了新的說法「全部出場」

**Step 1**: 在 `TradeActionDetector.__init__()` 中添加
```python
self.close_keywords = [
    '止盈出局',
    '出局',
    '全部平倉',
    # ... 其他
    '全部出場',  # ← 新增
]
```

**Step 2**: 添加測試
```python
def test_close_keyword_全部出場(self):
    message = "全部出場【盈利500】"
    assert detector.detect_close(message) is True
```

**Step 3**: 執行測試
```bash
python3 -m pytest tests/test_trade_action_detector.py::TestTradeActionDetectorClose::test_close_keyword_全部出場 -v
```

### 如何實施 PARTIAL_CLOSE？

參考 `detect_partial_close_percentage()` 的框架，已準備好：

```python
def detect_partial_close_percentage(self, message: str) -> Optional[float]:
    """當前未使用，為未來擴展預留"""
    match = re.search(r'(?:止盈|平)(\d+)%', message)
    if match:
        percentage = int(match.group(1))
        if 0 < percentage <= 100:
            return percentage / 100.0
    return None
```

**實施步驟**：
1. 解開 `PARTIAL_CLOSE` 關鍵詞匹配
2. 添加 `detect_partial_close()` 邏輯
3. 在 `refine_ai_result()` 中添加 `INFO → PARTIAL_CLOSE` 的轉換
4. 編寫 15+ 個測試用例
5. 測試覆蓋「止盈50% + 繼續持有」等複雜情況

---

## ⚠️ 重要注意事項

### 1. 不要直接修改 AI Parser 的結果

❌ **錯誤**：
```python
# 不要這樣做！
ai_result['action'] = detector.detect_close(msg) and 'CLOSE' or 'INFO'
```

✅ **正確**：
```python
# 使用 refine_ai_result()
ai_result = detector.refine_ai_result(ai_result, msg)
```

### 2. 優先信任 AI Parser

- AI Parser 已經解析過訊息，通常更準確
- 只在 AI 結果為 `INFO` 或明確無法判斷時才用 TradeActionDetector

### 3. 記錄所有修改

當 TradeActionDetector 修改了 AI 結果時，會添加 `_detector_refinement` 欄位：

```python
{
    'action': 'CLOSE',
    'symbol': 'BTCUSDT',
    '_detector_refinement': 'INFO→CLOSE by TradeActionDetector'
}
```

這供日後審計和改進使用。

### 4. 陳哥的複雜訊息

某些陳哥的訊息非常複雜，目前無法准確判別：

```
「中长线止盈50%做成本保护继续持有」
  ↓
  這是：部分平倉（50%）+ 止損移動（至成本價） + 繼續持有
  ↓
  需要：AI Parser + TradeActionDetector + 業務邏輯 共同處理
```

當前版本 TradeActionDetector 會讓 AI Parser 全權處理，不介入。

---

## 📊 決策樹

```
Discord 訊息
    ↓
AI Parser 解析
    ↓
AI 判斷為 CLOSE? ──是→ 轉發（不改動）
    ↓ 否
AI 判斷為 ENTRY? ──是→ 轉發（不改動）
    ↓ 否
AI 判斷為 CANCEL? ──是→ 轉發（不改動）
    ↓ 否
AI 判斷為 MOVE_SL? ──是→ 轉發（不改動）
    ↓ 否
AI 判斷為 INFO?
    ↓
TradeActionDetector.detect_close(message)?
    ├─是 → 改為 CLOSE + 記錄 _detector_refinement
    └─否 → 保持 INFO
```

---

## 🔗 相關文件

| 文件 | 用途 |
|------|------|
| `src/trade_action_detector.py` | 主要邏輯 |
| `tests/test_trade_action_detector.py` | 單元測試（24 個） |
| `src/signal_router.py` | 集成位置（待實施） |
| `src/ai_parser.py` | AI Parser（優先級 1） |

---

## 📞 問題排查

### Q: 我添加了關鍵詞但沒有生效？
A:
1. 檢查是否在 `__init__()` 中添加
2. 執行 `python3 -m pytest tests/test_trade_action_detector.py -v` 測試
3. 確認訊息中確實包含該關鍵詞

### Q: TradeActionDetector 改錯了怎麼辦？
A:
1. 查看日誌中的 `_detector_refinement` 欄位
2. 調整 `close_keywords` 或添加例外條件
3. 添加新的測試用例
4. 設定 `_ENABLE_DETECTOR = False` 臨時關閉（見下方）

### Q: 如何臨時關閉 TradeActionDetector？
A:
```python
# src/trade_action_detector.py 頂部
_ENABLE_DETECTOR = False  # 改為 False 臨時禁用

def refine_ai_result(self, ai_result, raw_message):
    if not _ENABLE_DETECTOR:
        return ai_result  # 直接返回，不修改
    # ... 其他邏輯
```

---

## 版本歷史

| 版本 | 日期 | 變更 |
|------|------|------|
| 0.1 | 2025-02-19 | 初版：CLOSE 偵測，24 個測試通過 |
| 0.2 (計畫) | 2025-Q2 | PARTIAL_CLOSE 實施 |
| 1.0 (計畫) | 2025-Q3 | AI 改進後可能無需此檢測器 |

---

## 聯繫人

- **擁有者**: Claude + Justin
- **最後更新**: 2025-02-19
- **測試狀態**: ✅ 24/24 通過
