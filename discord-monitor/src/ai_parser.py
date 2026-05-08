"""AI Signal Parser — uses Gemini to parse Discord trading signals into structured JSON."""
from __future__ import annotations

import asyncio
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
  "is_dca": false,
  "position_size_modifier": null
}

## 規則

### 基本規則
1. symbol 必須以 USDT 結尾。中文幣名映射：比特币/BTC → BTCUSDT, 以太坊/ETH → ETHUSDT。symbol 不分大小寫（btc = BTC）
2. 做多 = LONG, 做空 = SHORT。「做🈳」也是做空（🈳 是空的表情符號替代）。「方向：多」= LONG，「方向：空」= SHORT
3. 如果有入場價格區間（如 70800-72000），取中間值作為 entry_price。只有一個價格（"附近"）直接用該價格
4. 如果 TP 或 SL 寫「未設定」或「待定」，該欄位設為 null
5. 只輸出 JSON，不要任何解釋文字
5b. 如果一條訊息包含多個幣種的獨立訊號（如 BTC + ETH 分別有各自的方向/止損/止盈），輸出 JSON array，每個幣種一個 object

### ENTRY（開倉）判斷規則
6. 出現「附近，做多/做空/做🈳」→ ENTRY
7. 「市价做多/做空」→ ENTRY，用「实时价格」或「市价」後面的數字當 entry_price
8. 「换手做多/做空」→ CLOSE（先平原倉，新開倉會是下一條獨立訊息）
9. 📢 交易訊號發布 → ENTRY
10. 止盈如有多個用 / 或 - 分隔（如 87400/86800 或 105000-104500-104000），取第一個作為 take_profit
11. 倉位修飾語解析（position_size_modifier）：
   - 「輕倉」「小倉位」「試探性」→ position_size_modifier = 0.5
   - 「半倉」「半仓」→ position_size_modifier = 0.5
   - 「重倉」「全倉」「滿倉」「重仓」→ position_size_modifier = null（null = 預設 100%）
   - 無特別說明 → position_size_modifier = null
   - position_size_modifier 只適用於 ENTRY，其他 action 一律不帶
12. 「限价」或「限價單」只是下單類型說明，仍然是 ENTRY
35. 「合约策略」+「具体产品：BTC」+「进行方向：做多/做空」+「进场点位：X」→ ENTRY
36. 「Btc/Eth」+「方向：多/空」+「建仓：X」+「止损：X」+「止盈：X」→ ENTRY
37. 多點位入場（如「1）62588  2）60618」），取第一個點位作為 entry_price
38. 「仓位：20倍 总30%保证金」等倉位/槓桿描述 → 忽略（系統有自己的風控參數），仍然是 ENTRY

### CLOSE（平倉）判斷規則
13. 「手动平仓」「止盈出局」「保本出局」→ CLOSE
14. 「触发止损」「已经触发止损」「触发保本」「触发成本保护」「触发了X，這單出局」→ CLOSE
14b. 「出局」「出局观望」「落袋为安」「离场」→ CLOSE
14c. 「减仓」「减仓保护利润」「先减一些仓位」→ CLOSE + close_ratio（根據上下文判斷比例，預設 0.5）
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
20. 「上移止损<價格>」「止损修改至<價格>」「止损调整一下，X」→ MOVE_SL，new_stop_loss = 該價格
21. TP-SL 修改 / 訂單修改 → MOVE_SL
39. 「止盈暂设：X」「止盈修改：X」→ MOVE_SL + new_take_profit = X（設定止盈，不是平倉）
40. 「移动止损做无风险持仓」「移动止损到成本」→ MOVE_SL，new_stop_loss = null
41. 「止损移动到成本价X」「止损移动：X」→ MOVE_SL，new_stop_loss = X

### CANCEL（取消）判斷規則
22. 「限价单取消」「限价挂单取消」「掛單取消」→ CANCEL
23. ⚠️ 掛單取消 → CANCEL
42. 「策略作废」「作废」「撤掉」「撤销吧」→ CANCEL
43. 「取消吧」「没有成交的取消」→ CANCEL

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
44. 每日行情分析/交易思路（如「💰💰#比特币 X月X日交易思路」「GM日报」）→ INFO（含具體點位也是分析，不是下單指令）
45. 「策略继续等待」「今天没有策略」「继续持有」「不做调整」「策略继续挂着」→ INFO
46. 現貨/现货策略、现货建议 → INFO（系統只做合約）
47. 「汇报持仓情况」「发我你的利润」→ INFO（要求成員回報，不是交易指令）
48. 「仓位不做调整」「止损不变」「止损不动」→ INFO（確認現狀，無需操作）

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
輸出: {"action":"ENTRY","symbol":"BTCUSDT","side":"LONG","entry_price":67400,"position_size_modifier":0.5}

輸入: ETH轻仓做空，2650附近，止损2700
輸出: {"action":"ENTRY","symbol":"ETHUSDT","side":"SHORT","entry_price":2650,"stop_loss":2700,"position_size_modifier":0.5}

輸入: 📢 交易訊號發布: BTCUSDT\n做多 LONG 🟢 (限價單)\n入場價格 (Entry)\n95000\n止盈目標 (TP)\n98000\n止損價格 (SL)\n93000
輸出: {"action":"ENTRY","symbol":"BTCUSDT","side":"LONG","entry_price":95000,"stop_loss":93000,"take_profit":98000}

輸入: 合约策略\n具体产品：BTC\n进行方向： 做多\n进场点位：84700附近\n止损点位：83351\n止盈点位：待定
輸出: {"action":"ENTRY","symbol":"BTCUSDT","side":"LONG","entry_price":84700,"stop_loss":83351}

輸入: 合约策略（限价）\n具体产品：BTC\n进行方向： 做空\n进场点位：88500\n止损点位：89200\n止盈点位：87000
輸出: {"action":"ENTRY","symbol":"BTCUSDT","side":"SHORT","entry_price":88500,"stop_loss":89200,"take_profit":87000}

輸入: Btc\n方向：空\n建仓：105500-106000\n止损：106500\n止盈：105000-104500-104000\n个人建议，仅供参考，允许点差灵活进场不用踩点！
輸出: {"action":"ENTRY","symbol":"BTCUSDT","side":"SHORT","entry_price":105750,"stop_loss":106500,"take_profit":105000}

輸入: Eth\n方向：多\n建仓：2520附近\n止损：2510附近\n止盈：2550-2580-2610\n个人建议，仅供参考，允许点差灵活进场不用踩点！
輸出: {"action":"ENTRY","symbol":"ETHUSDT","side":"LONG","entry_price":2520,"stop_loss":2510,"take_profit":2550}

輸入: Btc\n方向：空\n建仓：105000附近\n止损：105600附近\n止盈：104500-104000-103500\n个人建议，仅供参考，允许点差灵活进场不用踩点！\nEth\n方向：空\n建仓：2510附近\n止损：2540附近\n止盈：2480-2450-2420\n个人建议，仅供参考，允许点差灵活进场不用踩点！
輸出: [{"action":"ENTRY","symbol":"BTCUSDT","side":"SHORT","entry_price":105000,"stop_loss":105600,"take_profit":104500},{"action":"ENTRY","symbol":"ETHUSDT","side":"SHORT","entry_price":2510,"stop_loss":2540,"take_profit":2480}]

輸入: BTC/USDT 做多  仓位：20倍 总30%保证金\n挂单点位：\n1）62588  15%保证金\n2）60618  15%保证金\n止盈目标：\n1）64100\n2）66200\n止損：59300\n策略仅供参考交流，控制好仓位，不作为做单依据，如有变更，另行通知。
輸出: {"action":"ENTRY","symbol":"BTCUSDT","side":"LONG","entry_price":62588,"stop_loss":59300,"take_profit":64100}

輸入: ETH/USDT 短线做空  仓位：20倍 总20%保证金\n点位：\n1）2911市价附近 20%保证金\n止盈目标：\n1）2888\n2）2845\n止損：2951
輸出: {"action":"ENTRY","symbol":"ETHUSDT","side":"SHORT","entry_price":2911,"stop_loss":2951,"take_profit":2888}

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

輸入: 止盈出局，盈利约1600点
輸出: {"action":"CLOSE","symbol":"BTCUSDT"}

輸入: 触发止损，这单亏损1500点
輸出: {"action":"CLOSE","symbol":"BTCUSDT"}

輸入: 止盈出局50%吧，这个行情真软
輸出: {"action":"CLOSE","symbol":"BTCUSDT","close_ratio":0.5}

輸入: 止盈50%，目前盈利900点 止损移动到94700。
輸出: {"action":"CLOSE","symbol":"BTCUSDT","close_ratio":0.5,"new_stop_loss":94700}

輸入: 62600止盈一半仓位，然后设置保本损
輸出: {"action":"CLOSE","symbol":"BTCUSDT","close_ratio":0.5,"new_stop_loss":null}

輸入: 空单马上出
輸出: {"action":"CLOSE","symbol":"BTCUSDT"}

輸入: 空头全部出局吧
輸出: {"action":"CLOSE","symbol":"BTCUSDT"}

輸入: 大饼目前浮盈出局
輸出: {"action":"CLOSE","symbol":"BTCUSDT"}

輸入: ETH多单出局，亏损几个点
輸出: {"action":"CLOSE","symbol":"ETHUSDT"}

輸入: 止盈30%仓位手动，执行。
輸出: {"action":"CLOSE","symbol":"BTCUSDT","close_ratio":0.3}

### MOVE_SL 範例

輸入: 止损设置: 89400
輸出: {"action":"MOVE_SL","symbol":"BTCUSDT","new_stop_loss":89400}

輸入: 止损上移至成本附近，做成本保护。
輸出: {"action":"MOVE_SL","symbol":"BTCUSDT"}

輸入: 做短线收益的可以全部走了，中长线收益剩余仓位上移止损67400
輸出: {"action":"MOVE_SL","symbol":"BTCUSDT","new_stop_loss":67400}

輸入: 訂單/TP-SL 修改: BTCUSDT\n做多 LONG Position Update\n入場價格 (Entry)\n67500\n最新止盈 (New TP)\n69200\n最新止損 (New SL)\n65000
輸出: {"action":"MOVE_SL","symbol":"BTCUSDT","side":"LONG","new_stop_loss":65000,"new_take_profit":69200}

輸入: 止损移动到成本价84700
輸出: {"action":"MOVE_SL","symbol":"BTCUSDT","new_stop_loss":84700}

輸入: 止损移动：74200
輸出: {"action":"MOVE_SL","symbol":"BTCUSDT","new_stop_loss":74200}

輸入: 止盈暂设：81680
輸出: {"action":"MOVE_SL","symbol":"BTCUSDT","new_take_profit":81680}

輸入: 止盈修改：88207
輸出: {"action":"MOVE_SL","symbol":"BTCUSDT","new_take_profit":88207}

輸入: 先移动止损做无风险持仓
輸出: {"action":"MOVE_SL","symbol":"BTCUSDT","new_stop_loss":null}

輸入: 所有人注意，以太止损调整至 2528
輸出: {"action":"MOVE_SL","symbol":"ETHUSDT","new_stop_loss":2528}

輸入: 2910的空单设置保本，立即执行。
輸出: {"action":"MOVE_SL","symbol":"BTCUSDT","new_stop_loss":null}

輸入: 止损调整一下，94000
輸出: {"action":"MOVE_SL","symbol":"BTCUSDT","new_stop_loss":94000}

### CANCEL 範例

輸入: 66000限价多单取消。
輸出: {"action":"CANCEL","symbol":"BTCUSDT"}

輸入: 限价单取消。
輸出: {"action":"CANCEL","symbol":"BTCUSDT"}

輸入: ⚠️ 掛單取消: ETHUSDT\n做空 SHORT 🔴
輸出: {"action":"CANCEL","symbol":"ETHUSDT","side":"SHORT"}

輸入: 比特币空单取消挂单，没吃上，也没有极速下跌，点位有点激进了 取消速度。
輸出: {"action":"CANCEL","symbol":"BTCUSDT"}

輸入: 已更新，前面的作废。
輸出: {"action":"CANCEL","symbol":"BTCUSDT"}

輸入: 限价策略撤销吧。等待新的单子
輸出: {"action":"CANCEL","symbol":"BTCUSDT"}

輸入: 上涨了，这笔限价单取消吧
輸出: {"action":"CANCEL","symbol":"BTCUSDT"}

輸入: 时间太久，大饼的多单可以撤销
輸出: {"action":"CANCEL","symbol":"BTCUSDT"}

### DCA 補倉範例

輸入: BTC掛70000補倉，SL修改到67000
輸出: {"action":"ENTRY","symbol":"BTCUSDT","entry_price":70000,"is_dca":true,"new_stop_loss":67000}

輸入: ETH在2400加倉，止損改到2300，止盈改到2800
輸出: {"action":"ENTRY","symbol":"ETHUSDT","entry_price":2400,"is_dca":true,"new_stop_loss":2300,"new_take_profit":2800}

輸入: BTC 68000附近可以补一点仓位，止损不变
輸出: {"action":"ENTRY","symbol":"BTCUSDT","entry_price":68000,"is_dca":true}

輸入: BTC，70900附近，做空，做一个限价补仓，止损统一修改71700
輸出: {"action":"ENTRY","symbol":"BTCUSDT","side":"SHORT","entry_price":70900,"is_dca":true,"new_stop_loss":71700}

輸入: ETH目前1787，补5%仓位，止损移动：1760
輸出: {"action":"ENTRY","symbol":"ETHUSDT","entry_price":1787,"is_dca":true,"new_stop_loss":1760}

輸入: 比特币目前74900附近，补多单，止损暂时设置74200
輸出: {"action":"ENTRY","symbol":"BTCUSDT","side":"LONG","entry_price":74900,"is_dca":true,"new_stop_loss":74200}

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

輸入: 💰💰#比特币 4月2日交易思路\n🤔盘面分析：近期市场波动会比较大\n🤔交易计划：\n1、关注84500—84000附近支撑结果，支跌低多，跌破追空。
輸出: {"action":"INFO"}

輸入: 策略继续等待
輸出: {"action":"INFO"}

輸入: 今天没有策略了，休息吧
輸出: {"action":"INFO"}

輸入: BTC多单继续持有
輸出: {"action":"INFO","symbol":"BTCUSDT"}

輸入: 汇报持仓情况
輸出: {"action":"INFO"}

輸入: 目前85750附近，仓位不做调整
輸出: {"action":"INFO","symbol":"BTCUSDT"}

輸入: GM20250618【Bitget】\n1）大盘走势\nbtc大跌，山寨普跌。市场缺乏热点。
輸出: {"action":"INFO"}

輸入: ⚠️现货策略⚠️\nETH目前1480附近\n现货可以买入20%仓位
輸出: {"action":"INFO","symbol":"ETHUSDT"}

輸入: 现货持仓更新～2024/5/10\n1、ORDI：持仓成本36.5～37，卖出目标：50.5全部清仓
輸出: {"action":"INFO"}

輸入: 策略9连胜，9连胜
輸出: {"action":"INFO"}

輸入: 止损不变，触发就出局
輸出: {"action":"INFO","symbol":"BTCUSDT"}
"""


# Image-mode system prompt — 在既有 SYSTEM_PROMPT 之上追加圖片專用規則。
# 設計原則：保留所有既有 schema 規則，加上「BTC-only + 從圖優先 + 文字補充」的 hint。
IMAGE_SYSTEM_PROMPT_SUFFIX = """

## 圖片訊號額外規則（multimodal mode）

當輸入包含圖片時：
1. **以圖片為主**：圖片中的數字（entry / SL / TP）優先於文字描述
2. **僅處理 BTC**：若圖片或文字明確是其他幣（ETH、SOL 等），回傳 {"action": "INFO", "symbol": "OTHER"}
3. **文字補充驗證**：若文字提到的價格與圖片一致 → 提高信心；若不一致 → 以圖片為準並在輸出加 "discrepancy_note" 欄位
4. **多幣別圖片**：若圖列多個幣（含 BTC），只抽 BTC 的部分
5. **過期訊號**：圖中含「上週」「回顧」「總結」「已平倉」等字樣 → 回傳 {"action": "INFO"}
6. **純技術分析圖**（K 線圖無交易計畫）→ 回傳 {"action": "INFO"}
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
        self._total_prompt_tokens = 0
        self._total_response_tokens = 0
        self._call_count = 0
        self._system_prompt = SYSTEM_PROMPT  # 預設硬編碼值，gRPC 推送後動態替換
        self._prompt_version = 0             # 0 = 使用硬編碼預設

        api_key = os.environ.get(config.api_key_env, "")
        if not api_key:
            logger.warning("AI parser: %s not set, AI parsing disabled", config.api_key_env)
            self.client = None
            return

        self.client = genai.Client(api_key=api_key)
        logger.info("AI parser initialized: model=%s", config.model)

    def update_system_prompt(self, new_prompt: str, version: int) -> None:
        """熱更新 system prompt（由 gRPC config push 觸發）。"""
        self._system_prompt = new_prompt
        self._prompt_version = version
        logger.info("System prompt 已更新: v%d (%d chars)", version, len(new_prompt))

    @property
    def prompt_version(self) -> int:
        """當前使用的 prompt 版本號（0 = 硬編碼預設）。"""
        return self._prompt_version

    def get_token_stats(self) -> dict:
        """回傳 session 累計的 token 統計（供 heartbeat 傳送）。"""
        return {
            "call_count": self._call_count,
            "total_prompt_tokens": self._total_prompt_tokens,
            "total_response_tokens": self._total_response_tokens,
        }

    async def parse(self, content: str) -> dict | None:
        """Parse a Discord signal message into a structured trade request.

        Retry strategy:
          - 429 / RESOURCE_EXHAUSTED → retry with exponential backoff
          - JSON decode error → no retry (AI returned garbage)
          - Other exceptions → no retry (fallback to regex)

        Returns:
            dict matching TradeRequest schema, or None on failure.
        """
        if not self.client:
            return None

        last_error = None
        for attempt in range(self.config.max_retries):
            try:
                response = await self.client.aio.models.generate_content(
                    model=self.config.model,
                    contents=content,
                    config=types.GenerateContentConfig(
                        system_instruction=self._system_prompt,
                        response_mime_type="application/json",
                        temperature=0.0,
                    ),
                )

                # 記錄 token 用量（錢已花，不管後續 parse 成不成功都記）
                usage = getattr(response, 'usage_metadata', None)
                if usage:
                    prompt_tokens = getattr(usage, 'prompt_token_count', 0) or 0
                    response_tokens = getattr(usage, 'candidates_token_count', 0) or 0
                    total_tokens = getattr(usage, 'total_token_count', 0) or 0
                    self._total_prompt_tokens += prompt_tokens
                    self._total_response_tokens += response_tokens
                    self._call_count += 1
                    logger.info(
                        "AI tokens: prompt=%d, response=%d, total=%d "
                        "(session: %d calls, avg prompt=%d)",
                        prompt_tokens, response_tokens, total_tokens,
                        self._call_count,
                        self._total_prompt_tokens // max(self._call_count, 1),
                    )

                text = response.text.strip()
                parsed = json.loads(text)

                # 防禦：Gemini 有時會回傳 JSON array（複雜訊號含多段時）
                # 例如 [{"action":"ENTRY",...}, {"action":"INFO",...}]
                if isinstance(parsed, list):
                    logger.warning(
                        "AI parser: got list (%d items), extracting best signal: %s",
                        len(parsed), text[:200],
                    )
                    parsed = self._pick_best_from_list(parsed)
                    if parsed is None:
                        return None

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
                # JSON 格式錯 → 不重試（AI 回垃圾，重試也一樣）
                logger.warning("AI parser: invalid JSON response: %s", e)
                return None

            except Exception as e:
                last_error = e
                error_str = str(e)
                is_rate_limit = "429" in error_str or "RESOURCE_EXHAUSTED" in error_str

                if is_rate_limit and attempt < self.config.max_retries - 1:
                    delay = self.config.retry_delays[
                        min(attempt, len(self.config.retry_delays) - 1)
                    ]
                    logger.warning(
                        "AI parser: rate limited (429), retry %d/%d after %ds",
                        attempt + 1,
                        self.config.max_retries,
                        delay,
                    )
                    await asyncio.sleep(delay)
                    continue

                # 非 429 錯誤 or 最後一次重試 → 放棄
                logger.warning("AI parser: request failed: %s", e)
                return None

        logger.error(
            "AI parser: all %d retries exhausted: %s",
            self.config.max_retries,
            last_error,
        )
        return None

    async def parse_with_image(
        self,
        text_content: str,
        image_bytes: bytes,
        mime_type: str,
    ) -> dict | None:
        """Parse a Discord message that contains an image (with optional accompanying text).

        Uses Gemini multimodal: text + inline image bytes. The system prompt is the
        existing SYSTEM_PROMPT plus IMAGE_SYSTEM_PROMPT_SUFFIX (BTC-only rules).

        Retry strategy: identical to parse() — 429 retries with exponential backoff.

        Args:
            text_content: Optional text accompanying the image (may be empty string).
            image_bytes: Raw image bytes (PNG / JPEG / GIF / WebP).
            mime_type: Image MIME type (e.g. "image/png").

        Returns:
            dict matching TradeRequest schema, or None on failure.
        """
        if not self.client:
            return None

        # 建構 multimodal contents：文字 + 圖片 Part
        text_part = text_content if text_content else "[圖片訊號 — 請從圖中解析]"
        image_part = types.Part.from_bytes(data=image_bytes, mime_type=mime_type)
        # google-genai 接受 list[str | Part]，第一個是文字、第二個是圖片
        contents = [text_part, image_part]

        # 圖片模式 system prompt = 文字 prompt + 圖片專用規則
        image_system_prompt = self._system_prompt + IMAGE_SYSTEM_PROMPT_SUFFIX

        last_error = None
        for attempt in range(self.config.max_retries):
            try:
                response = await self.client.aio.models.generate_content(
                    model=self.config.model,
                    contents=contents,
                    config=types.GenerateContentConfig(
                        system_instruction=image_system_prompt,
                        response_mime_type="application/json",
                        temperature=0.0,
                    ),
                )

                # Token 統計（與 parse() 一致）
                usage = getattr(response, "usage_metadata", None)
                if usage:
                    prompt_tokens = getattr(usage, "prompt_token_count", 0) or 0
                    response_tokens = getattr(usage, "candidates_token_count", 0) or 0
                    total_tokens = getattr(usage, "total_token_count", 0) or 0
                    self._total_prompt_tokens += prompt_tokens
                    self._total_response_tokens += response_tokens
                    self._call_count += 1
                    logger.info(
                        "AI image tokens: prompt=%d response=%d total=%d (image_size=%d B)",
                        prompt_tokens, response_tokens, total_tokens, len(image_bytes),
                    )

                text = response.text.strip()
                parsed = json.loads(text)

                # 多訊號 list 處理（與 parse() 一致）
                if isinstance(parsed, list):
                    logger.warning(
                        "AI image parser: got list (%d items), picking best",
                        len(parsed),
                    )
                    parsed = self._pick_best_from_list(parsed)
                    if parsed is None:
                        return None

                if not self._validate(parsed):
                    logger.warning(
                        "AI image parser: validation failed for: %s", text[:200],
                    )
                    return None

                logger.info(
                    "AI image parsed: action=%s symbol=%s side=%s",
                    parsed.get("action"),
                    parsed.get("symbol"),
                    parsed.get("side"),
                )
                return parsed

            except json.JSONDecodeError as e:
                logger.warning("AI image parser: invalid JSON: %s", e)
                return None

            except Exception as e:
                last_error = e
                error_str = str(e)
                is_rate_limit = "429" in error_str or "RESOURCE_EXHAUSTED" in error_str

                if is_rate_limit and attempt < self.config.max_retries - 1:
                    delay = self.config.retry_delays[
                        min(attempt, len(self.config.retry_delays) - 1)
                    ]
                    logger.warning(
                        "AI image parser: rate limited, retry %d/%d after %ds",
                        attempt + 1, self.config.max_retries, delay,
                    )
                    await asyncio.sleep(delay)
                    continue

                logger.warning("AI image parser: request failed: %s", e)
                return None

        logger.error(
            "AI image parser: all %d retries exhausted: %s",
            self.config.max_retries, last_error,
        )
        return None

    def _pick_best_from_list(self, items: list) -> dict | None:
        """When Gemini returns a JSON array, pick the most actionable signal.

        Priority: ENTRY > CLOSE > MOVE_SL > CANCEL > INFO
        If multiple ENTRY signals exist (e.g. prefix + standard), pick the one
        with the most complete data (has stop_loss and take_profit).
        """
        if not items:
            return None

        # Filter to dicts only
        dicts = [item for item in items if isinstance(item, dict) and item.get("action")]
        if not dicts:
            return None

        # If only one, use it
        if len(dicts) == 1:
            return dicts[0]

        # Priority ranking
        priority = {"ENTRY": 5, "CLOSE": 4, "MOVE_SL": 3, "CANCEL": 2, "INFO": 1}

        # Sort by: action priority desc, then completeness desc
        def score(d: dict) -> tuple:
            action_score = priority.get(d.get("action", ""), 0)
            # Completeness: count how many key fields are present
            completeness = sum(1 for k in ("stop_loss", "take_profit", "entry_price", "side")
                               if d.get(k) is not None)
            return (action_score, completeness)

        best = max(dicts, key=score)
        logger.info(
            "AI parser: picked %s %s from %d candidates",
            best.get("action"), best.get("symbol"), len(dicts),
        )
        return best

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
            # 驗證 position_size_modifier（如果有的話）
            modifier = parsed.get("position_size_modifier")
            if modifier is not None:
                if not isinstance(modifier, (int, float)) or modifier <= 0 or modifier > 1:
                    return False
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
