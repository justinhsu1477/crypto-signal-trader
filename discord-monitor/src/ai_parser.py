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
  "new_stop_loss": null,
  "new_take_profit": null
}

## 規則
1. symbol 必須以 USDT 結尾（例如 BTC → BTCUSDT, ETH → ETHUSDT）
2. 做多 = LONG, 做空 = SHORT
3. 如果有入場價格區間（如 70800-72000），取中間值作為 entry_price
4. 如果 TP 或 SL 寫「未設定」，該欄位設為 null
5. 📢 交易訊號發布 → action = "ENTRY"
6. ⚠️ 掛單取消 → action = "CANCEL"（只需 symbol）
7. TP-SL 修改 / 訂單修改 → action = "MOVE_SL"（需要 symbol + new_stop_loss 和/或 new_take_profit）
8. 訊息中出現「平倉」二字 → action = "CLOSE"（例如：平倉離場、平倉、Closed）
9. 🚀 訊號成交 / 🛑 止損出場 / 💰 盈虧更新 → action = "INFO"
10. 無法辨識的訊息 → action = "INFO"
11. 只輸出 JSON，不要任何解釋文字

## 範例

輸入: 📢 交易訊號發布: BTCUSDT\n做多 LONG 🟢 (限價單)\n入場價格 (Entry)\n95000\n止盈目標 (TP)\n98000\n止損價格 (SL)\n93000
輸出: {"action":"ENTRY","symbol":"BTCUSDT","side":"LONG","entry_price":95000,"stop_loss":93000,"take_profit":98000}

輸入: ⚠️ 掛單取消: ETHUSDT\n做空 SHORT 🔴
輸出: {"action":"CANCEL","symbol":"ETHUSDT","side":"SHORT"}

輸入: 訂單/TP-SL 修改: BTCUSDT\n做多 LONG Position Update\n入場價格 (Entry)\n67500\n最新止盈 (New TP)\n69200\n最新止損 (New SL)\n65000
輸出: {"action":"MOVE_SL","symbol":"BTCUSDT","side":"LONG","new_stop_loss":65000,"new_take_profit":69200}

輸入: 平倉離場 (Closed): BTCUSDT\n做空 SHORT
輸出: {"action":"CLOSE","symbol":"BTCUSDT","side":"SHORT"}

輸入: BTCUSDT 平倉
輸出: {"action":"CLOSE","symbol":"BTCUSDT"}

輸入: 🚀 訊號成交: BTCUSDT 已成交
輸出: {"action":"INFO","symbol":"BTCUSDT"}
"""


class AiSignalParser:
    """Parses trading signals using Google Gemini."""

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

        if not action or not symbol:
            return False

        # Symbol must end with USDT
        if not symbol.endswith("USDT"):
            parsed["symbol"] = symbol + "USDT"

        if action == "ENTRY":
            return all([
                parsed.get("side") in ("LONG", "SHORT"),
                parsed.get("entry_price"),
                parsed.get("stop_loss"),
            ])

        if action == "CANCEL":
            return True  # Only symbol needed

        if action == "MOVE_SL":
            # Need at least one of new_stop_loss or new_take_profit
            return bool(parsed.get("new_stop_loss") or parsed.get("new_take_profit"))

        if action == "CLOSE":
            return True

        if action == "INFO":
            return True

        return False
