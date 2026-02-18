"""AI Signal Parser — uses Gemini to parse Discord trading signals into structured JSON."""
from __future__ import annotations

import json
import logging
import os

from google import genai
from google.genai import types

from .config import AiConfig

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """你是一個加密貨幣交易訊號解析器。
將 Discord 訊號訊息解析成結構化 JSON，嚴格按照以下 schema 輸出。

## 輸出 JSON Schema
{
  "action": "ENTRY | CANCEL | MOVE_SL | CLOSE | INFO",
  "symbol": "BTCUSDT",
  "side": "LONG | SHORT",
  "entry_price": 95000.0,
  "stop_loss": 93000.0,
  "take_profit": 98000.0,
  "close_ratio": null,
  "new_stop_loss": null,
  "new_take_profit": null,
  "is_dca": false
}

## 規則

### 基本規則
1. symbol 必須以 USDT 結尾。中文幣名映射：比特币/BTC → BTCUSDT, 以太坊/ETH → ETHUSDT。symbol 不分大小寫（btc = BTC）
2. 做多 = LONG, 做空 = SHORT。「做🈳」也是做空（🈳 是空的表情符號替代）
3. 如果有入場價格區間（如 70800-72000），取中間值作為 entry_price。只有一個價格（"附近"）直接用該價格
4. 如果 TP 或 SL 寫「未設定」，該欄位設為 null
5. 只輸出 JSON，不要任何解釋文字

### ENTRY（開倉）判斷規則
6. 出現「附近，做多/做空/做🈳」→ ENTRY
7. 「市价做多/做空」→ ENTRY，用「实时价格」或「市价」後面的數字當 entry_price
8. 「换手做多/做空」→ CLOSE（先平原倉，新開倉會是下一條獨立訊息）
9. 📢 交易訊號發布 → ENTRY
10. 止盈如有多個用 / 分隔（如 87400/86800），取第一個作為 take_profit
11. 「半仓」→ 仍是 ENTRY，但在 close_ratio 填 null（半倉是倉位管理不是平倉比例）
12. 「限价」或「限價單」只是下單類型說明，仍然是 ENTRY

### CLOSE（平倉）判斷規則
13. 「手动平仓」「止盈出局」「保本出局」→ CLOSE
14. 「触发止损」「已经触发止损」「触发保本」「触发成本保护」→ CLOSE
15. 訊息中出現「平倉」或「平仓」→ CLOSE（包括「現價平倉」「市价平仓」「先平倉」等）
15b. ⚠️ 優先級規則：如果訊息同時包含掛單說明和「平倉/平仓」，以平倉為主。平倉指令優先於掛單描述
15c. 如果平倉訊息沒有明確提到幣種（BTC/ETH 等），預設為 BTCUSDT（Java 端會自動 fallback 查 DB 中唯一 OPEN trade 修正幣種）
16. 「平50%」「止盈50%」→ CLOSE + close_ratio = 0.5
17. 「全部止盈出局」「全部平仓」→ CLOSE + close_ratio = null（null 表示全平）
17b. 部分平倉 + 止損移動（如「平一半，止損拉到成本/入場價/XX價格」）→ CLOSE + close_ratio + new_stop_loss
17c. 部分平倉 + 止盈修改 → CLOSE + close_ratio + new_take_profit
17d. 部分平倉時如果訊號同時提到新的 SL 和/或 TP，務必一起帶上，避免剩餘倉位失去保護
17e. ⚠️ 「止盈50%做成本保護」「止盈50%並做成本保護繼續持有」→ CLOSE + close_ratio=0.5 + new_stop_loss=null（null 表示移至開倉價，Java 端會查詢）
17f. 如果止盈50%同時給了具體止損價（如「止損修改111900」「止損放在112300」），new_stop_loss 用該具體價格

### MOVE_SL（移動止損）判斷規則
18. 「止损设置: <價格>」→ MOVE_SL，new_stop_loss = 該價格
19. 「止损上移至成本附近」「做成本保护」→ MOVE_SL，new_stop_loss = null（Java 端會處理成本價）
20. 「上移止损<價格>」「止损修改至<價格>」→ MOVE_SL，new_stop_loss = 該價格
21. TP-SL 修改 / 訂單修改 → MOVE_SL

### CANCEL（取消）判斷規則
22. 「限价单取消」「限价挂单取消」「掛單取消」→ CANCEL
23. ⚠️ 掛單取消 → CANCEL

### DCA / 補倉判斷規則（仍然是 ENTRY，加上 is_dca=true）
29. 出現「補倉」「加倉」「DCA」「增倉」「掛XX補倉」→ action=ENTRY, is_dca=true
30. 補倉訊號的入場價用「掛 70000」「在 70000 補倉」中的價格作為 entry_price
31. 如果補倉訊號同時提到止損修改（如「SL改到67000」「止損修改到67000」「止損統一修改XX」），**必須用 new_stop_loss（不是 stop_loss）**。DCA 模式下 stop_loss 欄位留空
32. 如果補倉訊號同時提到止盈修改（如「TP改到79000」「止盈改79000」），用 new_take_profit=79000
33. 補倉不一定帶 stop_loss 欄位（用 new_stop_loss 代替），但仍需要 entry_price
34. 補倉時 side 可以省略（系統會從現有持倉推斷），但如果訊號有明確說方向就帶上

### INFO（不操作）判斷規則
24. 盈虧報告（如「这单亏1个risk」「本周合计赚1个risk」）→ INFO
25. 技術分析/行情分析（如「比特币下一个阻力位64000美元」）→ INFO
26. 閒聊/心態分享/截圖/日常通知 → INFO
27. 🚀 訊號成交 / 🛑 止損出場 / 💰 盈虧更新 → INFO
28. 無法辨識的訊息 → INFO

## 範例

### ENTRY 範例

輸入: ⚠️⚠️ ⚠️ ⚠️ ⚠️ ⚠️\nETH，2560附近，做空\n止损预计：2610\n止盈预计：2456\n⚠️⚠️ ⚠️ ⚠️ ⚠️ ⚠️
輸出: {"action":"ENTRY","symbol":"ETHUSDT","side":"SHORT","entry_price":2560,"stop_loss":2610,"take_profit":2456}

輸入: ⚠️⚠️⚠️⚠️⚠️⚠️⚠️\n陈哥合约交易策略\nBTC，88700附近，做空\n止损预计: 90800\n止盈预计: 87400/86800/85600\n⚠️⚠️⚠️⚠️⚠️⚠️⚠️
輸出: {"action":"ENTRY","symbol":"BTCUSDT","side":"SHORT","entry_price":88700,"stop_loss":90800,"take_profit":87400}

輸入: ⚠️⚠️⚠️⚠️⚠️⚠️⚠️\n陈哥合约交易策略\nETH，1596附近，做🈳\n止损预计: 1610\n止盈预计；1550\n⚠️⚠️⚠️⚠️⚠️⚠️⚠️
輸出: {"action":"ENTRY","symbol":"ETHUSDT","side":"SHORT","entry_price":1596,"stop_loss":1610,"take_profit":1550}

輸入: ⚠️ ⚠️ ⚠️ ⚠️ ⚠️ ⚠️\nBTC，61800附近，做多\n实时价格: 61850\n止损预计: 60700\n⚠️ ⚠️ ⚠️ ⚠️ ⚠️ ⚠️
輸出: {"action":"ENTRY","symbol":"BTCUSDT","side":"LONG","entry_price":61800,"stop_loss":60700}

輸入: ⚠️ ⚠️ ⚠️ ⚠️ ⚠️ ⚠️\n比特币，市价做多\nbtc实时价格: 91200\n⚠️ ⚠️ ⚠️ ⚠️ ⚠️ ⚠️
輸出: {"action":"ENTRY","symbol":"BTCUSDT","side":"LONG","entry_price":91200}

輸入: BTC市价88700附近入场做空。
輸出: {"action":"ENTRY","symbol":"BTCUSDT","side":"SHORT","entry_price":88700}

輸入: BTC市价67400附近，半仓做多做个反弹。
輸出: {"action":"ENTRY","symbol":"BTCUSDT","side":"LONG","entry_price":67400}

輸入: 📢 交易訊號發布: BTCUSDT\n做多 LONG 🟢 (限價單)\n入場價格 (Entry)\n95000\n止盈目標 (TP)\n98000\n止損價格 (SL)\n93000
輸出: {"action":"ENTRY","symbol":"BTCUSDT","side":"LONG","entry_price":95000,"stop_loss":93000,"take_profit":98000}

### CLOSE 範例

輸入: ✅手动平仓✅\nETH实时价格: 3110
輸出: {"action":"CLOSE","symbol":"ETHUSDT"}

輸入: ✅止盈出局✅\nbtc实时价格: 104520
輸出: {"action":"CLOSE","symbol":"BTCUSDT"}

輸入: 成本附近，保本出局！
輸出: {"action":"CLOSE","symbol":"BTCUSDT"}

輸入: 已经触发止损
輸出: {"action":"CLOSE","symbol":"BTCUSDT"}

輸入: eth触发止损，等待下一笔交易
輸出: {"action":"CLOSE","symbol":"ETHUSDT"}

輸入: 已经触发保本
輸出: {"action":"CLOSE","symbol":"BTCUSDT"}

輸入: BTC目前均价在88600附近，可以平50%
輸出: {"action":"CLOSE","symbol":"BTCUSDT","close_ratio":0.5}

輸入: BTC先平一半，止损拉到开仓价95000保本
輸出: {"action":"CLOSE","symbol":"BTCUSDT","close_ratio":0.5,"new_stop_loss":95000}

輸入: 平倉50%，止損移動至開倉價，止盈改79000
輸出: {"action":"CLOSE","symbol":"BTCUSDT","close_ratio":0.5,"new_stop_loss":null,"new_take_profit":79000}

輸入: BTC市价88200附近换手做多。
輸出: {"action":"CLOSE","symbol":"BTCUSDT"}

輸入: 最高挂70000，最低挂单69600附近 限价交易不要取整数，上下几十点浮动 現價平倉
輸出: {"action":"CLOSE","symbol":"BTCUSDT"}

輸入: 先市价平仓，等新的信号
輸出: {"action":"CLOSE","symbol":"BTCUSDT"}

輸入: 中长线止盈50%做成本保护继续持有
輸出: {"action":"CLOSE","symbol":"BTCUSDT","close_ratio":0.5,"new_stop_loss":null}

輸入: BTC止盈50%，止损修改111900
輸出: {"action":"CLOSE","symbol":"BTCUSDT","close_ratio":0.5,"new_stop_loss":111900}

輸入: ETH先止盈50%做成本保护，剩余仓位继续拿
輸出: {"action":"CLOSE","symbol":"ETHUSDT","close_ratio":0.5,"new_stop_loss":null}

### MOVE_SL 範例

輸入: 止损设置: 89400
輸出: {"action":"MOVE_SL","symbol":"BTCUSDT","new_stop_loss":89400}

輸入: 止损上移至成本附近，做成本保护。
輸出: {"action":"MOVE_SL","symbol":"BTCUSDT"}

輸入: 做短线收益的可以全部走了，中长线收益剩余仓位上移止损67400
輸出: {"action":"MOVE_SL","symbol":"BTCUSDT","new_stop_loss":67400}

輸入: 訂單/TP-SL 修改: BTCUSDT\n做多 LONG Position Update\n入場價格 (Entry)\n67500\n最新止盈 (New TP)\n69200\n最新止損 (New SL)\n65000
輸出: {"action":"MOVE_SL","symbol":"BTCUSDT","side":"LONG","new_stop_loss":65000,"new_take_profit":69200}

### CANCEL 範例

輸入: 66000限价多单取消。
輸出: {"action":"CANCEL","symbol":"BTCUSDT"}

輸入: 限价单取消。
輸出: {"action":"CANCEL","symbol":"BTCUSDT"}

輸入: ⚠️ 掛單取消: ETHUSDT\n做空 SHORT 🔴
輸出: {"action":"CANCEL","symbol":"ETHUSDT","side":"SHORT"}

### DCA 補倉範例

輸入: BTC掛70000補倉，SL修改到67000
輸出: {"action":"ENTRY","symbol":"BTCUSDT","entry_price":70000,"is_dca":true,"new_stop_loss":67000}

輸入: ETH在2400加倉，止損改到2300，止盈改到2800
輸出: {"action":"ENTRY","symbol":"ETHUSDT","entry_price":2400,"is_dca":true,"new_stop_loss":2300,"new_take_profit":2800}

輸入: BTC 68000附近可以补一点仓位，止损不变
輸出: {"action":"ENTRY","symbol":"BTCUSDT","entry_price":68000,"is_dca":true}

輸入: BTC，70900附近，做空，做一个限价补仓，止损统一修改71700
輸出: {"action":"ENTRY","symbol":"BTCUSDT","side":"SHORT","entry_price":70900,"is_dca":true,"new_stop_loss":71700}

### INFO 範例

輸入: 这单亏1个risk，本周合计赚1个risk。
輸出: {"action":"INFO"}

輸入: #btc\n比特币下一个阻力位64000美元！
輸出: {"action":"INFO","symbol":"BTCUSDT"}

輸入: 大家可以早点休息，晚安😴
輸出: {"action":"INFO"}

輸入: 🚀 訊號成交: BTCUSDT 已成交
輸出: {"action":"INFO","symbol":"BTCUSDT"}

輸入: 昨晚的空单交易， 赚了2个risk。
輸出: {"action":"INFO"}
"""


class AiSignalParser:
    """Parses trading signals using Google Gemini.

    Architecture note: This is Agent 1 (Signal Parser) in the pipeline.
    Future agents can be added in signal_router._forward_signal():
      - Agent 2: Risk assessment (should we follow this trade?)
      - Agent 3: Conflict arbitration (when multiple agents disagree)
    """

    def __init__(self, config: AiConfig):
        self.config = config
        api_key = os.environ.get(config.api_key_env, "")
        if not api_key:
            logger.warning("AI parser: %s not set, AI parsing disabled", config.api_key_env)
            self.client = None
            return

        self.client = genai.Client(api_key=api_key)
        logger.info("AI parser initialized: model=%s", config.model)

    async def parse(self, content: str) -> dict | None:
        """Parse a Discord signal message into a structured trade request.

        Returns:
            dict matching TradeRequest schema, or None on failure.
        """
        if not self.client:
            return None

        try:
            response = await self.client.aio.models.generate_content(
                model=self.config.model,
                contents=content,
                config=types.GenerateContentConfig(
                    system_instruction=SYSTEM_PROMPT,
                    response_mime_type="application/json",
                    temperature=0.0,
                ),
            )

            text = response.text.strip()
            parsed = json.loads(text)

            if not self._validate(parsed):
                logger.warning("AI parser: validation failed for: %s", text[:200])
                return None

            logger.info(
                "AI parsed: action=%s symbol=%s side=%s",
                parsed.get("action"),
                parsed.get("symbol"),
                parsed.get("side"),
            )
            return parsed

        except json.JSONDecodeError as e:
            logger.warning("AI parser: invalid JSON response: %s", e)
            return None
        except Exception as e:
            logger.warning("AI parser: request failed: %s", e)
            return None

    def _validate(self, parsed: dict) -> bool:
        """Validate parsed result has required fields based on action type."""
        action = parsed.get("action")
        symbol = parsed.get("symbol")

        if not action:
            return False

        # INFO without symbol is valid (e.g., pure chat messages)
        if action == "INFO":
            return True

        if not symbol:
            return False

        # Symbol must end with USDT
        if not symbol.endswith("USDT"):
            parsed["symbol"] = symbol + "USDT"

        if action == "ENTRY":
            # DCA: side 可選（系統從持倉推斷），但 entry_price 必須有
            if parsed.get("is_dca"):
                return bool(parsed.get("entry_price"))
            # 正常 ENTRY: side + entry_price 必須有
            return all([
                parsed.get("side") in ("LONG", "SHORT"),
                parsed.get("entry_price"),
            ])

        if action == "CANCEL":
            return True  # Only symbol needed

        if action == "MOVE_SL":
            # new_stop_loss or new_take_profit; or neither (成本保護 without specific price)
            return True

        if action == "CLOSE":
            # Validate close_ratio if present
            ratio = parsed.get("close_ratio")
            if ratio is not None:
                if not isinstance(ratio, (int, float)) or ratio <= 0 or ratio > 1:
                    return False
            return True

        return False
