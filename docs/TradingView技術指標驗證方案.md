# TradingView 技術指標驗證整合方案

**文件版本：** 1.0  
**更新日期：** 2026-02-12  
**目標：** 在 AI 解析 Discord 訊號後，透過 TradingView 技術指標進行二次驗證

---

## 目錄

1. [系統架構](#一系統架構)
2. [三種整合方案對比](#二三種整合方案對比)
3. [推薦方案：TradingView API + AI 驗證](#三推薦方案tradingview-api--ai-驗證)
4. [完整代碼實現](#四完整代碼實現)
5. [驗證規則設計](#五驗證規則設計)
6. [其他可擴展功能](#六其他可擴展功能)
7. [測試方案](#七測試方案)
8. [實施計劃](#八實施計劃)

---

## 一、系統架構

### 1.1 當前流程

```
Discord 訊號 
  ↓
Python AI 解析 (Gemini)
  ↓
Java 風控檢查
  ↓
Binance 下單
```

### 1.2 新流程（加入 TradingView 驗證）

```
Discord 訊號 
  ↓
Python AI 解析 (Gemini)
  ↓
TradingView 技術指標驗證 ← 新增
  ├─ 獲取技術指標 (RSI, MACD, EMA...)
  ├─ AI 技術面分析
  └─ 綜合評分決策
  ↓
Java 風控檢查
  ↓
Binance 下單
```

### 1.3 驗證流程圖

```
AI 解析訊號
  ↓
action == "ENTRY"? ─No→ 直接發送到 Java
  ↓ Yes
獲取 TradingView 技術指標
  ↓
AI 技術面分析
  ↓
綜合評分 (技術指標 60% + AI 40%)
  ↓
confidence >= 60%? ─No→ 拒絕訊號，發送通知
  ↓ Yes
發送到 Java API (附帶驗證結果)
```

---


## 二、三種整合方案對比

### 方案 1：TradingView Webhook

**架構：**
```
Discord 訊號 → AI 解析 → 觸發 TradingView Alert 
                          ↓
                    TradingView 計算指標
                          ↓
                    Webhook 回傳結果 → Java API
                          ↓
                    風控 + 下單
```

**優點：**
- ✅ TradingView 的指標計算最準確（官方數據）
- ✅ 可以使用 Pine Script 自定義複雜策略
- ✅ 支援多種技術指標組合
- ✅ 不需要自己維護 K 線數據

**缺點：**
- ❌ 需要 TradingView Pro/Premium 訂閱（支援 Webhook）
- ❌ 有延遲（通常 1-3 秒）
- ❌ 需要預先設定 Alert
- ❌ 實現複雜度較高

**成本：** TradingView Pro: $14.95/月 或 Premium: $59.95/月

---

### 方案 2：TradingView 非官方 API（推薦）⭐⭐⭐

**架構：**
```
Discord 訊號 → AI 解析 → Python 調用 tradingview-ta
                          ↓
                    獲取技術指標 (RSI, MACD, EMA...)
                          ↓
                    AI 技術面分析
                          ↓
                    綜合評分 → Java API
```

**優點：**
- ✅ 免費
- ✅ 即時獲取（1-2 秒內）
- ✅ 不需要 TradingView 訂閱
- ✅ 簡單易用
- ✅ 支援多時間框架（1m, 5m, 15m, 1h, 4h, 1d）
- ✅ 提供 TradingView 綜合建議（BUY/SELL/NEUTRAL）

**缺點：**
- ⚠️ 非官方 API，可能不穩定
- ⚠️ 無法自定義指標邏輯（只能用預設的）
- ⚠️ 依賴第三方套件

**成本：** 免費

**Python 套件：**
```bash
pip install tradingview-ta
```

---

### 方案 3：Binance API + 自己計算

**架構：**
```
Discord 訊號 → AI 解析 → 從 Binance 獲取 K 線
                          ↓
                    使用 TA-Lib 計算指標
                          ↓
                    AI 技術面分析
                          ↓
                    綜合評分 → Java API
```

**優點：**
- ✅ 完全自主控制
- ✅ 可以自定義任何指標
- ✅ 免費
- ✅ 數據來源可靠（直接從 Binance）

**缺點：**
- ❌ 需要自己維護計算邏輯
- ❌ 需要處理 K 線數據
- ❌ 計算可能有誤差
- ❌ 開發成本高

**成本：** 免費

---

### 方案對比總結

| 特性 | Webhook | 非官方 API | 自己計算 |
|------|---------|-----------|---------|
| 成本 | $15-60/月 | 免費 | 免費 |
| 準確度 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ |
| 延遲 | 1-3 秒 | 1-2 秒 | <1 秒 |
| 實現難度 | 高 | 低 | 高 |
| 自定義能力 | 高 | 低 | 高 |
| 穩定性 | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ |

**推薦：方案 2（TradingView 非官方 API）**
- 免費且簡單
- 適合快速實現
- 後續可以升級到方案 1 或方案 3

---


## 三、推薦方案：TradingView API + AI 驗證

### 3.1 技術架構

```python
# 新增模組
discord-monitor/src/signal_validator.py  # 訊號驗證器
discord-monitor/requirements.txt         # 新增 tradingview-ta

# 修改模組
discord-monitor/src/signal_router.py     # 整合驗證流程
```

### 3.2 工作流程

```
1. Discord 訊號進入
   ↓
2. AI 解析成結構化 JSON
   ↓
3. 檢查 action == "ENTRY"?
   ├─ No → 直接發送到 Java
   └─ Yes → 進入驗證流程
       ↓
4. 獲取 TradingView 技術指標
   - RSI (14)
   - MACD
   - EMA (20, 50)
   - TradingView 綜合建議
   - 買入/賣出訊號數量
   ↓
5. AI 技術面分析
   - 評估訊號方向是否與指標一致
   - 檢查 RSI 是否超買/超賣
   - 確認 MACD 和 EMA 趨勢
   - 計算風險報酬比
   - 給出信心分數 (0-100)
   ↓
6. 綜合評分
   - 技術指標評分 (0-100)
   - AI 評分 (0-100)
   - 最終信心度 = 技術 60% + AI 40%
   ↓
7. 決策
   - confidence >= 60% → 通過驗證
   - confidence < 60% → 拒絕訊號
   ↓
8. 發送到 Java API (附帶驗證結果)
```

### 3.3 驗證規則

#### 技術指標評分規則（滿分 100）

**1. TradingView 綜合建議（40 分）**
```python
if signal['side'] == 'LONG':
    if summary == 'STRONG_BUY': score += 40
    elif summary == 'BUY': score += 30
    elif summary == 'NEUTRAL': score += 20
    else: score += 0  # SELL/STRONG_SELL
    
if signal['side'] == 'SHORT':
    if summary == 'STRONG_SELL': score += 40
    elif summary == 'SELL': score += 30
    elif summary == 'NEUTRAL': score += 20
    else: score += 0  # BUY/STRONG_BUY
```

**2. RSI 檢查（20 分）**
```python
rsi = indicators['rsi']

if signal['side'] == 'LONG':
    if rsi < 30: score += 20  # 超賣，適合做多
    elif 30 <= rsi <= 50: score += 10  # 正常偏低
    else: score += 0  # 偏高或超買
    
if signal['side'] == 'SHORT':
    if rsi > 70: score += 20  # 超買，適合做空
    elif 50 <= rsi <= 70: score += 10  # 正常偏高
    else: score += 0  # 偏低或超賣
```

**3. MACD 檢查（20 分）**
```python
macd_bullish = indicators['macd'] > indicators['macd_signal']

if signal['side'] == 'LONG' and macd_bullish:
    score += 20
elif signal['side'] == 'SHORT' and not macd_bullish:
    score += 20
else:
    score += 0  # 方向不一致
```

**4. EMA 趨勢（20 分）**
```python
ema_bullish = indicators['ema_20'] > indicators['ema_50']

if signal['side'] == 'LONG' and ema_bullish:
    score += 20
elif signal['side'] == 'SHORT' and not ema_bullish:
    score += 20
else:
    score += 0  # 趨勢不一致
```

#### AI 評分規則（0-100）

AI 會綜合考慮：
- 訊號方向與技術指標的一致性
- 入場價格的合理性
- 止損位置的適當性
- 風險報酬比
- 市場環境（波動度、成交量等）

#### 最終決策規則

```python
# 綜合評分
final_confidence = (technical_score * 0.6 + ai_score * 0.4) / 100

# 決策條件（必須同時滿足）
approved = (
    ai_analysis['approved'] == True and
    technical_score >= 50 and      # 技術指標至少 50 分
    final_confidence >= 0.6        # 綜合信心至少 60%
)
```

---


## 四、完整代碼實現

### 4.1 安裝依賴

```bash
# discord-monitor/requirements.txt
tradingview-ta==3.3.0
```

```bash
cd discord-monitor
pip install tradingview-ta
```

### 4.2 訊號驗證器（signal_validator.py）

```python
# discord-monitor/src/signal_validator.py

"""Signal Validator — validates trading signals using TradingView indicators and AI."""
import json
import logging
from typing import Optional

from google import genai
from google.genai import types
from tradingview_ta import TA_Handler, Interval

logger = logging.getLogger(__name__)


class SignalValidator:
    """
    訊號驗證器：結合 TradingView 技術指標和 AI 判斷
    """
    
    # 時間框架映射
    INTERVAL_MAP = {
        '1m': Interval.INTERVAL_1_MINUTE,
        '5m': Interval.INTERVAL_5_MINUTES,
        '15m': Interval.INTERVAL_15_MINUTES,
        '1h': Interval.INTERVAL_1_HOUR,
        '4h': Interval.INTERVAL_4_HOURS,
        '1d': Interval.INTERVAL_1_DAY,
    }
    
    def __init__(self, ai_client: genai.Client, interval: str = '15m'):
        """
        Args:
            ai_client: Gemini AI client
            interval: 時間框架 (1m, 5m, 15m, 1h, 4h, 1d)
        """
        self.ai_client = ai_client
        self.interval = self.INTERVAL_MAP.get(interval, Interval.INTERVAL_15_MINUTES)
        self.interval_str = interval
        
    async def validate(self, signal: dict) -> dict:
        """
        驗證交易訊號
        
        Args:
            signal: AI 解析後的訊號 dict
            
        Returns:
            {
                'approved': bool,
                'confidence': float,
                'technical_score': float,
                'ai_score': float,
                'reason': str,
                'warnings': list,
                'technical_indicators': dict
            }
        """
        symbol = signal.get('symbol')
        side = signal.get('side')
        
        logger.info("Validating signal: %s %s", symbol, side)
        
        # 1. 獲取技術指標
        technical = self._get_technical_indicators(symbol)
        if not technical:
            return {
                'approved': False,
                'confidence': 0.0,
                'technical_score': 0.0,
                'ai_score': 0.0,
                'reason': '無法獲取技術指標',
                'warnings': ['TradingView API 失敗'],
                'technical_indicators': None
            }
        
        # 2. AI 技術面分析
        ai_analysis = await self._ai_technical_analysis(signal, technical)
        
        # 3. 綜合評分
        result = self._calculate_final_score(signal, technical, ai_analysis)
        
        logger.info(
            "Validation result: %s %s @ %s → approved=%s confidence=%.2f (tech=%.2f ai=%.2f)",
            symbol, side, signal.get('entry_price'),
            result['approved'], result['confidence'],
            result['technical_score'], result['ai_score']
        )
        
        return result
    
    def _get_technical_indicators(self, symbol: str) -> Optional[dict]:
        """
        從 TradingView 獲取技術指標
        
        Args:
            symbol: 交易對，如 BTCUSDT
            
        Returns:
            技術指標 dict 或 None
        """
        try:
            # BTCUSDT → BTC
            base_symbol = symbol.replace('USDT', '')
            
            handler = TA_Handler(
                symbol=base_symbol,
                screener="crypto",
                exchange="BINANCE",
                interval=self.interval
            )
            
            analysis = handler.get_analysis()
            
            indicators = {
                # 綜合建議
                'summary': analysis.summary['RECOMMENDATION'],  # BUY/SELL/NEUTRAL/STRONG_BUY/STRONG_SELL
                'buy_signals': analysis.summary['BUY'],
                'sell_signals': analysis.summary['SELL'],
                'neutral_signals': analysis.summary['NEUTRAL'],
                
                # 振盪指標
                'rsi': analysis.indicators.get('RSI', 50),
                'rsi_14': analysis.indicators.get('RSI[1]', 50),
                'stoch_k': analysis.indicators.get('Stoch.K', 50),
                'stoch_d': analysis.indicators.get('Stoch.D', 50),
                
                # MACD
                'macd': analysis.indicators.get('MACD.macd', 0),
                'macd_signal': analysis.indicators.get('MACD.signal', 0),
                
                # 移動平均線
                'ema_10': analysis.indicators.get('EMA10', 0),
                'ema_20': analysis.indicators.get('EMA20', 0),
                'ema_50': analysis.indicators.get('EMA50', 0),
                'ema_100': analysis.indicators.get('EMA100', 0),
                'ema_200': analysis.indicators.get('EMA200', 0),
                'sma_10': analysis.indicators.get('SMA10', 0),
                'sma_20': analysis.indicators.get('SMA20', 0),
                'sma_50': analysis.indicators.get('SMA50', 0),
                
                # 價格與成交量
                'close': analysis.indicators.get('close', 0),
                'volume': analysis.indicators.get('volume', 0),
                'change': analysis.indicators.get('change', 0),
                'change_percent': analysis.indicators.get('change', 0) / analysis.indicators.get('close', 1) * 100 if analysis.indicators.get('close') else 0,
            }
            
            logger.info(
                "TradingView indicators (%s): summary=%s buy=%d sell=%d RSI=%.2f MACD=%.4f",
                self.interval_str,
                indicators['summary'],
                indicators['buy_signals'],
                indicators['sell_signals'],
                indicators['rsi'],
                indicators['macd']
            )
            
            return indicators
            
        except Exception as e:
            logger.error("Failed to get TradingView indicators: %s", e)
            return None

    
    async def _ai_technical_analysis(self, signal: dict, technical: dict) -> dict:
        """
        AI 技術面分析
        
        Args:
            signal: 交易訊號
            technical: 技術指標
            
        Returns:
            AI 分析結果 dict
        """
        prompt = f"""你是專業技術分析師。評估這個交易訊號：

訊號：
- 交易對: {signal['symbol']}
- 方向: {signal['side']}
- 入場價: {signal.get('entry_price', 'N/A')}
- 止損: {signal.get('stop_loss', 'N/A')}
- 止盈: {signal.get('take_profit', 'N/A')}

技術指標（{self.interval_str}）：
- TradingView 綜合: {technical['summary']} ({technical['buy_signals']} 買入 / {technical['sell_signals']} 賣出 / {technical['neutral_signals']} 中性)
- RSI(14): {technical['rsi']:.2f}
- MACD: {technical['macd']:.4f} (訊號線: {technical['macd_signal']:.4f})
- EMA(20): {technical['ema_20']:.2f}
- EMA(50): {technical['ema_50']:.2f}
- 當前價格: {technical['close']:.2f}
- 價格變化: {technical['change_percent']:.2f}%

評估標準：
1. 方向一致性：訊號方向是否與技術指標一致？
2. 超買超賣：RSI 是否在合理範圍？
   - RSI < 30: 超賣，適合做多
   - RSI > 70: 超買，適合做空
   - 30-70: 正常範圍
3. 趨勢確認：MACD 和 EMA 是否支持該方向？
   - MACD > Signal: 看漲
   - EMA(20) > EMA(50): 上升趨勢
4. 風險報酬比：如果有止損和止盈，計算風險報酬比
   - RR = (TP - Entry) / (Entry - SL)
   - 建議 RR >= 1.5

輸出 JSON（不要任何解釋）：
{{
    "approved": true/false,
    "confidence": 0-100,
    "reason": "簡短原因（中文，50字內）",
    "risk_reward_ratio": 數字或null,
    "warnings": ["警告1", "警告2"]
}}
"""
        
        try:
            response = await self.ai_client.aio.models.generate_content(
                model="gemini-2.0-flash-exp",
                contents=prompt,
                config=types.GenerateContentConfig(
                    response_mime_type='application/json',
                    temperature=0.0
                )
            )
            
            result = json.loads(response.text)
            logger.info("AI analysis: approved=%s confidence=%d reason=%s", 
                       result.get('approved'), result.get('confidence'), result.get('reason'))
            return result
            
        except Exception as e:
            logger.error("AI technical analysis failed: %s", e)
            return {
                'approved': False,
                'confidence': 0,
                'reason': 'AI 分析失敗',
                'warnings': [str(e)]
            }
    
    def _calculate_final_score(self, signal: dict, technical: dict, ai_analysis: dict) -> dict:
        """
        綜合評分
        
        Args:
            signal: 交易訊號
            technical: 技術指標
            ai_analysis: AI 分析結果
            
        Returns:
            最終評分結果
        """
        warnings = []
        side = signal.get('side')
        
        # ========== 技術指標評分（0-100）==========
        technical_score = 0
        
        # 1. TradingView 綜合建議（40 分）
        summary = technical['summary']
        if side == 'LONG':
            if summary == 'STRONG_BUY':
                technical_score += 40
            elif summary == 'BUY':
                technical_score += 30
            elif summary == 'NEUTRAL':
                technical_score += 20
                warnings.append('技術指標中性')
            else:
                warnings.append(f'技術指標建議 {summary}，與做多方向相反')
        elif side == 'SHORT':
            if summary == 'STRONG_SELL':
                technical_score += 40
            elif summary == 'SELL':
                technical_score += 30
            elif summary == 'NEUTRAL':
                technical_score += 20
                warnings.append('技術指標中性')
            else:
                warnings.append(f'技術指標建議 {summary}，與做空方向相反')
        
        # 2. RSI 檢查（20 分）
        rsi = technical['rsi']
        if side == 'LONG':
            if rsi < 30:
                technical_score += 20  # 超賣，適合做多
            elif 30 <= rsi <= 50:
                technical_score += 10  # 正常偏低
            else:
                warnings.append(f'RSI {rsi:.1f} 偏高，做多風險較大')
        elif side == 'SHORT':
            if rsi > 70:
                technical_score += 20  # 超買，適合做空
            elif 50 <= rsi <= 70:
                technical_score += 10  # 正常偏高
            else:
                warnings.append(f'RSI {rsi:.1f} 偏低，做空風險較大')
        
        # 3. MACD 檢查（20 分）
        macd_bullish = technical['macd'] > technical['macd_signal']
        if (side == 'LONG' and macd_bullish) or (side == 'SHORT' and not macd_bullish):
            technical_score += 20
        else:
            warnings.append('MACD 與訊號方向不一致')
        
        # 4. EMA 趨勢（20 分）
        ema_bullish = technical['ema_20'] > technical['ema_50']
        if (side == 'LONG' and ema_bullish) or (side == 'SHORT' and not ema_bullish):
            technical_score += 20
        else:
            warnings.append('EMA 趨勢與訊號方向不一致')
        
        # ========== AI 評分 ==========
        ai_score = ai_analysis.get('confidence', 0)
        if ai_analysis.get('warnings'):
            warnings.extend(ai_analysis['warnings'])
        
        # ========== 綜合評分 ==========
        # 技術指標 60%，AI 40%
        final_confidence = (technical_score * 0.6 + ai_score * 0.4) / 100
        
        # ========== 決策邏輯 ==========
        approved = (
            ai_analysis.get('approved', False) and
            technical_score >= 50 and      # 技術指標至少 50 分
            final_confidence >= 0.6        # 綜合信心至少 60%
        )
        
        return {
            'approved': approved,
            'confidence': final_confidence,
            'technical_score': technical_score / 100,
            'ai_score': ai_score / 100,
            'reason': ai_analysis.get('reason', ''),
            'risk_reward_ratio': ai_analysis.get('risk_reward_ratio'),
            'warnings': warnings,
            'technical_indicators': technical
        }
```

---


### 4.3 整合到 Signal Router

```python
# discord-monitor/src/signal_router.py

from .signal_validator import SignalValidator

class SignalRouter:
    """Routes parsed signals to the appropriate handler."""
    
    def __init__(self, api_client: ApiClient, config: Config, ai_client):
        self.api_client = api_client
        self.config = config
        
        # 初始化驗證器
        self.validator = SignalValidator(
            ai_client=ai_client,
            interval='15m'  # 可以從 config 讀取
        )
    
    async def _forward_signal(self, parsed: dict, raw_message: str):
        """
        轉發訊號到 Java API（加入驗證）
        """
        action = parsed.get('action')
        symbol = parsed.get('symbol')
        
        logger.info("Forwarding signal: action=%s symbol=%s", action, symbol)
        
        # ========== 只對 ENTRY 訊號進行技術驗證 ==========
        if action == 'ENTRY':
            logger.info("Validating ENTRY signal with TradingView indicators...")
            
            validation = await self.validator.validate(parsed)
            
            # 記錄驗證結果
            logger.info(
                "Validation result: approved=%s confidence=%.2f technical=%.2f ai=%.2f warnings=%s",
                validation['approved'],
                validation['confidence'],
                validation['technical_score'],
                validation['ai_score'],
                validation['warnings']
            )
            
            # 如果不通過驗證
            if not validation['approved']:
                logger.warning(
                    "❌ Signal REJECTED by validator: %s %s @ %s",
                    symbol,
                    parsed.get('side'),
                    parsed.get('entry_price')
                )
                logger.warning("Reason: %s", validation['reason'])
                logger.warning("Warnings: %s", validation['warnings'])
                
                # 發送拒絕通知（可選）
                await self._notify_rejection(parsed, validation)
                return
            
            # 通過驗證，將驗證結果附加到訊號中
            logger.info(
                "✅ Signal APPROVED: %s %s @ %s (confidence=%.2f)",
                symbol,
                parsed.get('side'),
                parsed.get('entry_price'),
                validation['confidence']
            )
            
            parsed['validation'] = {
                'confidence': validation['confidence'],
                'technical_score': validation['technical_score'],
                'ai_score': validation['ai_score'],
                'warnings': validation['warnings'],
                'technical_summary': validation['technical_indicators']['summary'] if validation['technical_indicators'] else None,
                'rsi': validation['technical_indicators']['rsi'] if validation['technical_indicators'] else None,
            }
        
        # ========== 繼續原有流程 ==========
        result = await self.api_client.send_trade(parsed, dry_run=self.config.dry_run)
        
        if result.success:
            logger.info("✅ Signal forwarded successfully: %s", result.summary[:200])
        else:
            logger.error("❌ Signal forwarding failed: %s", result.error)
    
    async def _notify_rejection(self, signal: dict, validation: dict):
        """
        發送訊號拒絕通知（可選功能）
        
        可以發送到：
        1. Discord 通知頻道
        2. 本地日誌
        3. 資料庫記錄
        """
        message = f"""
⚠️ 訊號被技術驗證拒絕

交易對: {signal['symbol']}
方向: {signal['side']}
入場價: {signal.get('entry_price')}
止損: {signal.get('stop_loss')}

驗證結果:
- 綜合信心度: {validation['confidence']:.1%}
- 技術指標評分: {validation['technical_score']:.1%}
- AI 評分: {validation['ai_score']:.1%}
- 原因: {validation['reason']}
- 警告: {', '.join(validation['warnings'])}

技術指標:
- TradingView 建議: {validation['technical_indicators']['summary'] if validation['technical_indicators'] else 'N/A'}
- RSI: {validation['technical_indicators']['rsi']:.2f if validation['technical_indicators'] else 'N/A'}
"""
        
        logger.info("Rejection notification: %s", message)
        
        # 可以在這裡發送到 Discord 或其他通知渠道
        # await discord_webhook.send(message)
```

---


### 4.4 配置文件更新

```yaml
# discord-monitor/config.yml

# ... 現有配置 ...

# 新增：訊號驗證配置
signal_validation:
  enabled: true                    # 是否啟用驗證
  interval: "15m"                  # 技術指標時間框架 (1m, 5m, 15m, 1h, 4h, 1d)
  min_confidence: 0.6              # 最低信心度閾值 (0.0-1.0)
  min_technical_score: 0.5         # 最低技術指標評分 (0.0-1.0)
  notify_rejection: true           # 是否通知被拒絕的訊號
  
  # 可選：多時間框架驗證
  multi_timeframe:
    enabled: false
    intervals: ["15m", "1h", "4h"]
    require_all_pass: false        # 是否要求所有時間框架都通過
```

---

## 五、驗證規則設計

### 5.1 評分權重配置

```python
# 可以根據實際情況調整權重

SCORING_WEIGHTS = {
    # 技術指標權重（總和 = 100）
    'tradingview_summary': 40,    # TradingView 綜合建議
    'rsi': 20,                    # RSI 指標
    'macd': 20,                   # MACD 指標
    'ema_trend': 20,              # EMA 趨勢
    
    # 最終評分權重
    'technical_weight': 0.6,      # 技術指標 60%
    'ai_weight': 0.4,             # AI 分析 40%
    
    # 決策閾值
    'min_technical_score': 50,    # 技術指標最低 50 分
    'min_confidence': 0.6,        # 綜合信心最低 60%
}
```

### 5.2 不同市場環境的調整

```python
def adjust_thresholds_by_volatility(volatility: float) -> dict:
    """
    根據市場波動度調整閾值
    
    Args:
        volatility: 24h 價格波動百分比
        
    Returns:
        調整後的閾值
    """
    if volatility > 10:  # 高波動
        return {
            'min_confidence': 0.7,      # 提高信心度要求
            'min_technical_score': 60,  # 提高技術指標要求
        }
    elif volatility < 3:  # 低波動
        return {
            'min_confidence': 0.5,      # 降低信心度要求
            'min_technical_score': 40,  # 降低技術指標要求
        }
    else:  # 正常波動
        return {
            'min_confidence': 0.6,
            'min_technical_score': 50,
        }
```

### 5.3 特殊情況處理

```python
# 1. TradingView API 失敗時的降級策略
if not technical_indicators:
    # 選項 A: 拒絕所有訊號（保守）
    return {'approved': False, 'reason': '無法獲取技術指標'}
    
    # 選項 B: 只用 AI 分析（激進）
    if ai_score >= 80:  # AI 非常有信心
        return {'approved': True, 'confidence': ai_score / 100}
    else:
        return {'approved': False, 'reason': '技術指標不可用且 AI 信心不足'}

# 2. 止損缺失時的處理
if signal['action'] == 'ENTRY' and not signal.get('stop_loss'):
    warnings.append('訊號缺少止損，風險極高')
    # 可以選擇直接拒絕
    return {'approved': False, 'reason': '缺少止損'}

# 3. 風險報酬比過低
if risk_reward_ratio and risk_reward_ratio < 1.0:
    warnings.append(f'風險報酬比過低 ({risk_reward_ratio:.2f})')
    # 降低信心度
    final_confidence *= 0.8
```

---


## 六、其他可擴展功能

### 6.1 多時間框架確認

```python
async def multi_timeframe_validation(self, signal: dict) -> dict:
    """
    多時間框架驗證
    
    檢查 15m, 1h, 4h 三個時間框架的趨勢是否一致
    """
    intervals = ['15m', '1h', '4h']
    results = []
    
    for interval in intervals:
        validator = SignalValidator(self.ai_client, interval)
        result = await validator.validate(signal)
        results.append({
            'interval': interval,
            'approved': result['approved'],
            'confidence': result['confidence'],
            'technical_score': result['technical_score']
        })
    
    # 計算平均信心度
    avg_confidence = sum(r['confidence'] for r in results) / len(results)
    
    # 至少 2 個時間框架通過
    approved_count = sum(1 for r in results if r['approved'])
    
    return {
        'approved': approved_count >= 2,
        'confidence': avg_confidence,
        'timeframes': results
    }
```

### 6.2 市場情緒分析

```python
def analyze_market_sentiment(symbol: str) -> dict:
    """
    分析市場情緒
    
    可以整合：
    1. Fear & Greed Index (恐慌貪婪指數)
    2. Funding Rate (資金費率)
    3. Open Interest (未平倉合約)
    4. Long/Short Ratio (多空比)
    """
    # 從 Binance API 獲取
    funding_rate = get_funding_rate(symbol)
    long_short_ratio = get_long_short_ratio(symbol)
    
    sentiment_score = 0
    
    # 資金費率分析
    if funding_rate > 0.01:  # 多頭過熱
        sentiment_score -= 20
    elif funding_rate < -0.01:  # 空頭過熱
        sentiment_score += 20
    
    # 多空比分析
    if long_short_ratio > 2:  # 多頭過多
        sentiment_score -= 20
    elif long_short_ratio < 0.5:  # 空頭過多
        sentiment_score += 20
    
    return {
        'sentiment_score': sentiment_score,
        'funding_rate': funding_rate,
        'long_short_ratio': long_short_ratio
    }
```

### 6.3 支撐阻力位檢測

```python
def check_support_resistance(symbol: str, entry_price: float, side: str) -> dict:
    """
    檢查入場價是否在關鍵支撐/阻力位附近
    
    使用 Pivot Points 或歷史高低點
    """
    # 獲取最近 100 根 K 線
    klines = get_klines(symbol, '1h', 100)
    
    # 計算 Pivot Points
    high = max(k['high'] for k in klines[-24:])  # 24h 最高
    low = min(k['low'] for k in klines[-24:])    # 24h 最低
    close = klines[-1]['close']
    
    pivot = (high + low + close) / 3
    r1 = 2 * pivot - low
    r2 = pivot + (high - low)
    s1 = 2 * pivot - high
    s2 = pivot - (high - low)
    
    # 檢查入場價是否接近關鍵位
    tolerance = 0.005  # 0.5% 容差
    
    if side == 'LONG':
        # 做多應該在支撐位附近
        near_support = (
            abs(entry_price - s1) / s1 < tolerance or
            abs(entry_price - s2) / s2 < tolerance
        )
        return {
            'valid': near_support,
            'reason': '入場價接近支撐位' if near_support else '入場價不在支撐位附近',
            'pivot_points': {'pivot': pivot, 's1': s1, 's2': s2, 'r1': r1, 'r2': r2}
        }
    else:
        # 做空應該在阻力位附近
        near_resistance = (
            abs(entry_price - r1) / r1 < tolerance or
            abs(entry_price - r2) / r2 < tolerance
        )
        return {
            'valid': near_resistance,
            'reason': '入場價接近阻力位' if near_resistance else '入場價不在阻力位附近',
            'pivot_points': {'pivot': pivot, 's1': s1, 's2': s2, 'r1': r1, 'r2': r2}
        }
```

### 6.4 成交量確認

```python
def volume_confirmation(symbol: str, side: str) -> dict:
    """
    確認成交量是否支持該方向
    
    突破伴隨放量更可靠
    """
    klines = get_klines(symbol, '15m', 50)
    
    # 計算平均成交量
    avg_volume = sum(k['volume'] for k in klines[:-1]) / (len(klines) - 1)
    current_volume = klines[-1]['volume']
    
    # 成交量比率
    volume_ratio = current_volume / avg_volume
    
    # 價格變化
    price_change = (klines[-1]['close'] - klines[-2]['close']) / klines[-2]['close']
    
    # 判斷
    if side == 'LONG' and price_change > 0 and volume_ratio > 1.5:
        return {'valid': True, 'reason': '上漲伴隨放量', 'volume_ratio': volume_ratio}
    elif side == 'SHORT' and price_change < 0 and volume_ratio > 1.5:
        return {'valid': True, 'reason': '下跌伴隨放量', 'volume_ratio': volume_ratio}
    else:
        return {'valid': False, 'reason': '成交量未確認', 'volume_ratio': volume_ratio}
```

### 6.5 相關性分析

```python
def correlation_check() -> dict:
    """
    檢查 BTC 與其他主流幣的相關性
    
    如果 BTC 下跌但 ETH 上漲，可能是假突破
    """
    btc_change = get_price_change('BTCUSDT', '1h')
    eth_change = get_price_change('ETHUSDT', '1h')
    
    # 相關性檢查
    if (btc_change > 0 and eth_change < 0) or (btc_change < 0 and eth_change > 0):
        return {
            'warning': True,
            'reason': 'BTC 與 ETH 走勢背離，需謹慎'
        }
    else:
        return {
            'warning': False,
            'reason': 'BTC 與 ETH 走勢一致'
        }
```

### 6.6 AI Agent 組合決策

```python
async def multi_agent_decision(signal: dict) -> dict:
    """
    多個 AI Agent 投票決策
    
    Agent 1: 技術面分析師
    Agent 2: 風險管理師
    Agent 3: 市場情緒分析師
    """
    # Agent 1: 技術面
    technical_agent = await ai_technical_analysis(signal)
    
    # Agent 2: 風險管理
    risk_agent = await ai_risk_analysis(signal)
    
    # Agent 3: 市場情緒
    sentiment_agent = await ai_sentiment_analysis(signal)
    
    # 投票
    votes = [
        technical_agent['approved'],
        risk_agent['approved'],
        sentiment_agent['approved']
    ]
    
    # 至少 2 票通過
    approved = sum(votes) >= 2
    
    # 平均信心度
    avg_confidence = (
        technical_agent['confidence'] +
        risk_agent['confidence'] +
        sentiment_agent['confidence']
    ) / 3
    
    return {
        'approved': approved,
        'confidence': avg_confidence / 100,
        'votes': {
            'technical': technical_agent,
            'risk': risk_agent,
            'sentiment': sentiment_agent
        }
    }
```

---


## 七、測試方案

### 7.1 單元測試

```python
# discord-monitor/tests/test_signal_validator.py

import pytest
from src.signal_validator import SignalValidator

@pytest.mark.asyncio
async def test_validate_long_signal_with_good_indicators():
    """測試做多訊號 + 良好技術指標"""
    signal = {
        'action': 'ENTRY',
        'symbol': 'BTCUSDT',
        'side': 'LONG',
        'entry_price': 95000,
        'stop_loss': 93000,
        'take_profit': 98000
    }
    
    validator = SignalValidator(ai_client, interval='15m')
    result = await validator.validate(signal)
    
    assert 'approved' in result
    assert 'confidence' in result
    assert 'technical_score' in result
    assert 'ai_score' in result
    
    # 如果 TradingView 建議 BUY，應該通過
    if result['technical_indicators']['summary'] in ['BUY', 'STRONG_BUY']:
        assert result['approved'] == True

@pytest.mark.asyncio
async def test_validate_short_signal_with_conflicting_indicators():
    """測試做空訊號 + 衝突的技術指標"""
    signal = {
        'action': 'ENTRY',
        'symbol': 'BTCUSDT',
        'side': 'SHORT',
        'entry_price': 95000,
        'stop_loss': 97000,
        'take_profit': 92000
    }
    
    validator = SignalValidator(ai_client, interval='15m')
    result = await validator.validate(signal)
    
    # 如果 TradingView 建議 BUY，做空應該被拒絕
    if result['technical_indicators']['summary'] in ['BUY', 'STRONG_BUY']:
        assert result['approved'] == False
        assert len(result['warnings']) > 0

@pytest.mark.asyncio
async def test_validate_without_stop_loss():
    """測試沒有止損的訊號"""
    signal = {
        'action': 'ENTRY',
        'symbol': 'BTCUSDT',
        'side': 'LONG',
        'entry_price': 95000,
        # 缺少 stop_loss
    }
    
    validator = SignalValidator(ai_client, interval='15m')
    result = await validator.validate(signal)
    
    # 應該有警告
    assert any('止損' in w for w in result['warnings'])
```

### 7.2 整合測試

```python
# discord-monitor/tests/test_signal_flow.py

@pytest.mark.asyncio
async def test_full_signal_flow_with_validation():
    """測試完整的訊號流程（含驗證）"""
    
    # 1. 模擬 Discord 訊號
    message = "📢 交易訊號發布: BTCUSDT\n做多 LONG\n入場價格 95000\n止損 93000\n止盈 98000"
    
    # 2. AI 解析
    parsed = await ai_parser.parse(message)
    assert parsed['action'] == 'ENTRY'
    
    # 3. 技術驗證
    validator = SignalValidator(ai_client)
    validation = await validator.validate(parsed)
    
    # 4. 檢查驗證結果
    assert 'approved' in validation
    assert 'confidence' in validation
    
    # 5. 如果通過，發送到 Java
    if validation['approved']:
        result = await api_client.send_trade(parsed)
        assert result.success == True
```

### 7.3 回測驗證

```python
# 回測腳本：測試驗證器的效果

async def backtest_validator():
    """
    回測驗證器
    
    使用歷史訊號測試：
    1. 有多少訊號被正確拒絕（避免虧損）
    2. 有多少訊號被正確通過（獲得盈利）
    3. 有多少訊號被錯誤拒絕（錯過盈利）
    4. 有多少訊號被錯誤通過（導致虧損）
    """
    historical_signals = load_historical_signals()
    
    results = {
        'correct_reject': 0,  # 正確拒絕（避免虧損）
        'correct_pass': 0,    # 正確通過（獲得盈利）
        'false_reject': 0,    # 錯誤拒絕（錯過盈利）
        'false_pass': 0,      # 錯誤通過（導致虧損）
    }
    
    for signal in historical_signals:
        # 驗證
        validation = await validator.validate(signal)
        
        # 計算實際結果（假設持有到止損或止盈）
        actual_result = calculate_actual_result(signal)
        
        if validation['approved'] and actual_result > 0:
            results['correct_pass'] += 1
        elif validation['approved'] and actual_result < 0:
            results['false_pass'] += 1
        elif not validation['approved'] and actual_result < 0:
            results['correct_reject'] += 1
        elif not validation['approved'] and actual_result > 0:
            results['false_reject'] += 1
    
    # 計算準確率
    accuracy = (results['correct_pass'] + results['correct_reject']) / len(historical_signals)
    
    print(f"驗證器準確率: {accuracy:.2%}")
    print(f"正確拒絕: {results['correct_reject']}")
    print(f"正確通過: {results['correct_pass']}")
    print(f"錯誤拒絕: {results['false_reject']}")
    print(f"錯誤通過: {results['false_pass']}")
```

### 7.4 實際場景測試

```bash
# 測試場景 1: 強烈看漲訊號
訊號: BTC 做多 @ 95000, SL 93000, TP 98000
TradingView: STRONG_BUY, RSI 25, MACD 金叉
預期: 通過驗證 ✅

# 測試場景 2: 衝突訊號
訊號: BTC 做空 @ 95000, SL 97000, TP 92000
TradingView: STRONG_BUY, RSI 25, MACD 金叉
預期: 拒絕驗證 ❌

# 測試場景 3: 中性市場
訊號: BTC 做多 @ 95000, SL 93000, TP 98000
TradingView: NEUTRAL, RSI 50, MACD 平緩
預期: 可能通過（取決於 AI 分析）⚠️

# 測試場景 4: 缺少止損
訊號: BTC 做多 @ 95000, TP 98000 (無 SL)
預期: 拒絕驗證 ❌

# 測試場景 5: TradingView API 失敗
訊號: BTC 做多 @ 95000, SL 93000, TP 98000
TradingView: API 失敗
預期: 拒絕驗證（保守策略）❌
```

---


## 八、實施計劃

### 8.1 開發階段（預計 3-5 天）

#### Phase 1: 基礎實現（1-2 天）
- [ ] 安裝 `tradingview-ta` 套件
- [ ] 實現 `SignalValidator` 類
- [ ] 實現技術指標獲取功能
- [ ] 實現基礎評分邏輯
- [ ] 單元測試

#### Phase 2: AI 整合（1 天）
- [ ] 實現 AI 技術面分析
- [ ] 設計 AI prompt
- [ ] 測試 AI 分析準確性
- [ ] 調整 prompt 優化結果

#### Phase 3: 系統整合（1 天）
- [ ] 整合到 `SignalRouter`
- [ ] 更新配置文件
- [ ] 實現拒絕通知功能
- [ ] 整合測試

#### Phase 4: 測試與優化（1 天）
- [ ] 實際場景測試
- [ ] 調整評分權重
- [ ] 調整信心度閾值
- [ ] 性能優化

### 8.2 測試階段（預計 1-2 週）

#### Week 1: Testnet 測試
- [ ] 在 Binance Testnet 運行
- [ ] 記錄所有驗證結果
- [ ] 分析通過率和準確率
- [ ] 調整參數

#### Week 2: 小額實盤測試
- [ ] 使用小額資金（如 100 USDT）
- [ ] 監控驗證效果
- [ ] 收集真實數據
- [ ] 最終調整

### 8.3 上線階段

#### 上線前檢查清單
- [ ] 所有單元測試通過
- [ ] 整合測試通過
- [ ] Testnet 測試至少 1 週
- [ ] 小額實盤測試至少 3 天
- [ ] 驗證準確率 >= 70%
- [ ] 配置文件正確
- [ ] 日誌記錄完整
- [ ] 錯誤處理完善
- [ ] 性能測試通過（延遲 < 3 秒）

#### 上線後監控
- [ ] 每日檢查驗證通過率
- [ ] 每週分析驗證準確率
- [ ] 監控 TradingView API 穩定性
- [ ] 監控 AI API 成本
- [ ] 收集用戶反饋

### 8.4 成本估算

#### 開發成本
- 開發時間: 3-5 天
- 測試時間: 1-2 週
- 總計: 約 2-3 週

#### 運營成本（每月）
- TradingView API: 免費（使用非官方 API）
- Gemini AI API: 
  - 每個訊號約 2 次 AI 調用（解析 + 驗證）
  - 假設每天 20 個訊號 = 40 次調用
  - 每月約 1200 次調用
  - 成本: 約 $1-3/月（取決於 token 數量）
- 總計: 約 $1-3/月

#### ROI 分析
如果驗證器能：
- 避免 1 次重大虧損（-500 USDT）
- 或提高 10% 的勝率

則每月節省的成本遠超 $3 的運營成本。

---

## 九、風險與限制

### 9.1 技術風險

1. **TradingView API 不穩定**
   - 非官方 API 可能隨時失效
   - 緩解措施: 實現降級策略，API 失敗時只用 AI 分析

2. **AI 分析不準確**
   - AI 可能給出錯誤判斷
   - 緩解措施: 技術指標佔 60% 權重，降低 AI 影響

3. **延遲問題**
   - 驗證流程增加 1-3 秒延遲
   - 緩解措施: 優化代碼，使用異步調用

### 9.2 業務風險

1. **過度保守**
   - 可能拒絕太多好訊號
   - 緩解措施: 調整閾值，記錄被拒絕訊號的實際結果

2. **過度激進**
   - 可能通過太多壞訊號
   - 緩解措施: 提高信心度閾值

3. **市場環境變化**
   - 技術指標在某些市場環境下失效
   - 緩解措施: 根據市場波動度動態調整閾值

### 9.3 成本風險

1. **AI API 成本增加**
   - 訊號量增加導致成本上升
   - 緩解措施: 設定每日 AI 調用上限

2. **需要升級 TradingView 訂閱**
   - 如果要用官方 Webhook
   - 緩解措施: 先用免費方案，效果好再升級

---

## 十、總結

### 10.1 核心價值

1. **提高交易品質**
   - 過濾掉與技術面衝突的訊號
   - 降低虧損風險

2. **增加信心**
   - 有數據支持的決策
   - 減少情緒化交易

3. **可追溯性**
   - 記錄每個訊號的驗證結果
   - 方便回測和優化

### 10.2 關鍵指標

- **驗證通過率**: 目標 40-60%（過濾掉 40-60% 的訊號）
- **驗證準確率**: 目標 >= 70%（通過的訊號中 70% 盈利）
- **延遲**: < 3 秒
- **成本**: < $5/月

### 10.3 後續優化方向

1. **機器學習優化**
   - 收集歷史數據訓練模型
   - 自動調整評分權重

2. **多策略組合**
   - 不同市場環境使用不同策略
   - 動態切換驗證規則

3. **社區反饋**
   - 收集用戶反饋
   - 持續優化驗證邏輯

---

**文件版本：** 1.0  
**最後更新：** 2026-02-12  
**維護者：** Trading System Team  
**審閱狀態：** 待審閱

---

## 附錄

### A. 參考資料

- [TradingView Technical Analysis](https://github.com/brian-the-dev/python-tradingview-ta)
- [Binance API Documentation](https://binance-docs.github.io/apidocs/futures/en/)
- [Google Gemini API](https://ai.google.dev/docs)

### B. 相關文件

- `幣安交易流程文件.txt` - 完整交易流程
- `追加需求.md` - AI 智慧判斷需求
- `discord-monitor/src/ai_parser.py` - AI 訊號解析器

### C. 聯絡方式

如有問題或建議，請聯絡開發團隊。
