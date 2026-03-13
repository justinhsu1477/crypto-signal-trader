# Crypto Signal Trader — 功能擴展路線圖

> 訊號來源管理 → 每日日報 → 訊號準確度分析

---

## Phase 1：訊號來源管理（Signal Source Management）✅ 已完成

**目標**：統一管理所有訊號頻道，支援 SHADOW/MANUAL 模式，可接入大量頻道但不實際交易。

### 核心功能
- **訊號來源 CRUD**：新增/編輯/刪除/啟停用訊號來源
- **路由模式**：ASSIGNED（指定用戶）/ GLOBAL（廣播全員）
- **監聽模式**：LIVE（實際交易）/ SHADOW（影子模式，僅記錄不下單）/ MANUAL（手動確認）
- **用戶綁定**：每個來源可指派特定用戶
- **績效查詢**：按來源統計訊號數、成功率、平均信心分數
- **GLOBAL 唯一限制**：同時只能有一個 GLOBAL 來源

### 關鍵檔案
| 類型 | 檔案 |
|------|------|
| Entity | `SignalSource.java` |
| Repository | `SignalSourceRepository.java` |
| Service | `SignalSourceService.java` |
| Controller | `AdminDashboardController.java`（6 端點） |
| Migration | `V31__add_signal_sources.sql`, `V32__add_monitor_config_to_signal_sources.sql` |
| 前端 | `admin/signal-sources/page.tsx` |

---

## Phase 2：每日訊號日報（Daily Signal Report）✅ 已完成

**目標**：每天 23:59（台北時間）自動彙整所有頻道的交易訊號，產生結構化統計 + Gemini AI 分析日報。

### 核心功能
- **自動排程**：`@Scheduled(cron = "0 59 23 * * *")` 每日產生報告
- **結構化統計**：
  - 按 source 分組：訊號數、LONG/SHORT 比、平均信心分數、動作分佈
  - 按 symbol 分組：最活躍幣種 Top 10
  - 整體：總訊號、LONG/SHORT 比例、平均 AI 信心
- **Gemini AI 分析**：超過 3 筆訊號時呼叫 Gemini，提供宏觀趨勢分析
- **Admin 通知**：Discord 推送日報摘要
- **手動補跑**：支援指定日期重新產生報告
- **前端頁面**：分頁列表 + 點擊展開詳情（AI 分析、來源統計、活躍幣種）

### 關鍵檔案
| 類型 | 檔案 |
|------|------|
| Entity | `DailySignalReport.java` |
| Repository | `DailySignalReportRepository.java` |
| Service | `DailySignalReportService.java` |
| DTO | `DailySignalReportResponse.java` |
| Controller | `AdminDashboardController.java`（3 端點） |
| Migration | `V33__add_daily_signal_report.sql` |
| 前端 | `admin/daily-reports/page.tsx` |
| 測試 | `DailySignalReportServiceTest.java` |

---

## Phase 3：訊號準確度分析（Signal Accuracy Analysis）🔜 待開發

**目標**：追蹤每個訊號的實際市場走勢，評估各來源的歷史準確率，幫助決定哪些頻道值得從 SHADOW 升級為 LIVE。

### 規劃功能
- **價格快照**：訊號產生時記錄當下價格，定期追蹤後續走勢（1h / 4h / 24h）
- **準確度計算**：比對訊號方向（LONG/SHORT）與實際漲跌
- **來源評分**：每個來源的歷史命中率、平均回報率、Sharpe Ratio
- **排行榜**：來源準確度排名，支援時間區間篩選
- **升級建議**：基於準確度數據，AI 建議哪些 SHADOW 來源可升級為 LIVE
- **前端儀表板**：視覺化各來源績效比較

### 預計新增
| 類型 | 說明 |
|------|------|
| Entity | `SignalAccuracy` — 訊號準確度追蹤記錄 |
| Service | `SignalAccuracyService` — 價格追蹤 + 準確度計算 |
| 排程 | 定期抓取幣安現貨/合約價格，比對歷史訊號 |
| Controller | Admin 端點 — 來源排行、準確度統計 |
| 前端 | `admin/signal-accuracy/page.tsx` — 排行榜 + 趨勢圖 |

---

## 整體架構流向

```
Python Monitor → 訊號接收 → BroadcastLog 記錄
                                    ↓
                    Phase 1: SignalSource 管理路由模式
                                    ↓
                    Phase 2: DailySignalReport 日報彙整
                                    ↓
                    Phase 3: SignalAccuracy 準確度追蹤
```
