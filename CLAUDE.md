# Crypto Signal Trader — Claude Code 開發指引

> 來自 29 個 session、579 則訊息的 insights 分析，提煉出的開發規則與最佳實踐。

---

## General Rules

- **問概念問題時，不要改檔案。** 當我問技術概念、架構解釋、面試準備相關問題時，只提供說明，不要建立、編輯、或修改任何檔案。除非我明確要求改 code。
- **先給精簡版，再展開。** 寫履歷、文件、或任何文字內容時，先給簡短版本（每條 < 15 字），等我選擇後再展開。不要一次輸出大量內容。
- **Commit message 用繁體中文。**

---

## Tech Stack

- **Java 17** / Spring Boot 3.2.5 / Gradle
- **PostgreSQL**（Neon Serverless Postgres）
- **Next.js**（前端 web-dashboard）
- 注意 Lombok 與高版本 JDK（24+）的相容性問題
- 使用 Java 17 慣例，除非另有指定

---

## Debugging 策略

修 bug 時，**必須先完成資料流追蹤，再提出修復方案**。不可直接跳到修改。

### 強制流程

1. **追蹤完整資料流**（必做）：
```
Controller → Service → Repository → Entity → DTO/Response
```

2. **記錄每一層的值**：在每個 handoff 點確認型別與欄位名稱是否一致

3. **定位根因層**：問題可能出在任何一層，不要假設問題出在「症狀出現」的那一層

4. **提出修復方案時，說明根因在哪一層**

### 禁止事項

- ❌ 不要只看 stack trace 就直接改「報錯的那一行」
- ❌ 不要跳過 Repository / Entity 層的檢查
- ❌ 不要在沒讀完相關檔案前就提出方案

### Commit 前驗證

修完 bug 後，用以下 prompt 自我檢查：
> 「從 Controller 到前端 render，追蹤修改的值，確認每個 handoff 傳遞正確的型別和欄位名。」

---

## Git & GitHub

- **Push 前必確認帳號**：執行 `git remote -v` 和 `gh auth status`，確認使用 `justinhsu1477` 帳號
- 不要自行 push，除非我明確要求
- 多個改動時，按任務分組 commit（不要一次 commit 所有變更）

---

## 面試準備模式

當我做面試準備時，用以下結構回答：

1. **是什麼**（1-2 句）
2. **在專案中怎麼用**（引用實際 code）
3. **為什麼選這個方案**
4. **Trade-offs**

面試準備 session 中 **不要改任何檔案**。

---

## 常見踩坑提醒

### 1. 搞錯目標檔案
專案有多個相似檔案（Entity/DTO/Controller），改之前：
- 確認完整檔案路徑
- 確認是正確的模組（trading vs dashboard vs subscription）

### 2. 過早行動
收到需求後，先確認理解正確再動手：
- 複雜任務用 TodoWrite 拆分步驟
- 不確定的地方先問，不要猜

### 3. Docker / 部署設定衝突
- `docker-compose.yml` 中 `image` 和 `build` 不能同時存在
- `.env` 中有特殊字元的值需要用雙引號包裹
- OAuth redirect URI 注意 `.app` vs `.dev` 網域差異

---

## 專案模組依賴（不可違反）

```
auth         → user
trading      → shared, notification
notification → shared
subscription → user, shared
dashboard    → trading, user, subscription, shared
referral     → user, shared
user         → (nothing)
shared       → (nothing)
```

**規則**：禁止循環依賴、禁止反向依賴

---

## 驗證流程

改完 code 後，依序執行：

```bash
# 後端編譯 + 測試
./gradlew build

# 前端建構
cd web-dashboard && npm run build
```

Java 檔案修改後，優先確認編譯通過再繼續下一步。
