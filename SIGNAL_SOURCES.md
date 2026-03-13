# 訊號來源設定總覽

> 最後更新：2026-03-13

## 來源清單

| ID | 名稱 | Channel ID | Trade Mode | Routing Mode | Enabled | Paper Trading |
|----|------|------------|------------|--------------|---------|---------------|
| 2 | fengge（風哥） | 1004720271409807400 | SHADOW | ASSIGNED | Yes | Yes |
| 3 | zhige（志哥） | 1004720695160348683 | SHADOW | ASSIGNED | Yes | No |
| 4 | shuqin（淑琴） | 1104301561892585543 | SHADOW | ASSIGNED | Yes | Yes |
| 5 | jiami-dapiaoliang（加密大漂亮） | 1229962956884807740 | SHADOW | ASSIGNED | Yes | Yes |
| 6 | sanmage（三馬哥） | 1237749772148801567 | SHADOW | ASSIGNED | Yes | Yes |
| 7 | ouyang（歐陽） | 1331646699843883069 | SHADOW | ASSIGNED | Yes | No |
| 8 | feiyang（飛揚） | 1356581750914027590 | SHADOW | ASSIGNED | Yes | No |
| 9 | dabiaoke（大鏢客） | 1384381529064476815 | SHADOW | ASSIGNED | Yes | No |
| 10 | chenge（陳哥） | 1271151178905817129 | SHADOW | GLOBAL | Yes | No |

> Guild ID 全部為 `1004707886657699901`

---

## Trade Mode 說明

| Mode | 行為 |
|------|------|
| **AUTO** | 真實交易：接收訊號 → 對所有訂閱用戶執行 Binance 下單 → 通知 Admin |
| **SHADOW** | 影子模式：記錄 BroadcastLog + AI 評分 + 可選模擬交易（Paper Trading）→ 不執行真實交易、不即時通知 Admin |
| **MANUAL** | 僅記錄：只寫 BroadcastLog，不交易、不評分（目前無來源使用） |

## Routing Mode 說明

| Mode | 行為 |
|------|------|
| **GLOBAL** | 全員廣播：訊號發送給所有啟用的用戶 |
| **ASSIGNED** | 指定用戶：只發送給綁定此來源的用戶 |

## Paper Trading（模擬交易）

- 開啟後，SHADOW 訊號會建立 `simulated=true` 的 Trade 紀錄
- 固定倉位 1000 USDT、10x 槓桿
- 定時監控（每 90 秒）檢查 TP/SL 自動模擬平倉
- 績效比較表會自動包含模擬交易的 PnL 數據

## Enabled 開關

- `enabled=false` 時，該來源的所有訊號會被跳過（不進入 AUTO/SHADOW/MANUAL 流程）
- 僅記錄一筆 `SOURCE_DISABLED` 的 BroadcastLog

---

## 變更紀錄

| 日期 | 變更 |
|------|------|
| 2026-03-13 | 陳哥（id=10）從 AUTO 改為 SHADOW（暫停真實交易，改為觀察模式） |
