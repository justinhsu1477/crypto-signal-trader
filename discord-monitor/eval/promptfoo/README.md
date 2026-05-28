# Promptfoo Eval — PoC

跟既有 `eval/runner.py` 並存的 Promptfoo 包裝，目標：拿到 HTML 報告 + 快取 +
A/B model 比較等紅利，**不改既有 scorer 邏輯**。

## 架構

```
promptfooconfig.yaml
   │
   ├─ prompts: '{{ input }}'          ← 從 case 拿 input 當 Gemini 輸入
   │
   ├─ providers: file://provider.py    ← 包 AiSignalParser（生產同條 code path）
   │
   ├─ tests: file://tests_loader.py    ← 從 cases.jsonl 動態生成
   │     ↑ 同一份資料源，不複製
   │
   └─ assert: file://assertion.py      ← 呼叫既有 scorer.py，rubric 不變
```

## 跟 `eval/runner.py` 的差異

| 維度 | runner.py | Promptfoo |
|---|---|---|
| 跑 cases.jsonl | ✅ | ✅（同一份）|
| 用 AiSignalParser | ✅ | ✅（透過 provider.py）|
| 用 scorer.py rubric | ✅ | ✅（透過 assertion.py）|
| 跑 Gemini | ✅ | ✅ |
| **HTML 視覺化報告** | ❌ | ✅ `promptfoo view` |
| **快取** | ❌ | ✅（相同 input 直接 hit）|
| **A/B model 比較** | 手寫 | ✅ built-in side-by-side |
| Token usage tracking | log only | ✅ JSON output |
| Discord webhook 通知 | ✅ format_report.py | （沒接，要寫 wrapper）|

## 本地跑

```bash
cd discord-monitor

# 一次性：建 venv 裝 python 依賴
python3 -m venv .venv-eval
source .venv-eval/bin/activate
pip install -r requirements.txt

# 一次性：裝 promptfoo
cd eval/promptfoo
npm install

# 設環境
export GEMINI_API_KEY=...                              # 必須
export EVAL_GEMINI_MODEL=gemini-2.5-flash              # 預設 gemini-2.5-flash-lite
export PROMPTFOO_PYTHON=$(which python)                # 用 venv 的 python

# 跑全部
npx promptfoo eval

# 跑前 5 個（快速驗）
EVAL_LIMIT=5 npx promptfoo eval

# 只跑 compound case
EVAL_FILTER=compound npx promptfoo eval

# 看 HTML 報告
npx promptfoo view
```

## 環境變數

| Var | 必須？ | 預設 | 說明 |
|---|---|---|---|
| `GEMINI_API_KEY` | ✅ | — | Gemini API key |
| `EVAL_GEMINI_MODEL` | ❌ | `gemini-2.5-flash-lite` | 模型版本 |
| `EVAL_LIMIT` | ❌ | (跑全部) | 只跑前 N 個 case |
| `EVAL_FILTER` | ❌ | (全跑) | 限 case id prefix（如 `compound`）|
| `PROMPTFOO_PYTHON` | ❌ | `python3` | 要用 venv python 才能 import src.ai_parser |

## 此 PoC 範圍

- ✅ 功能驗證：38 case 都能跑、scorer 結果一致
- ✅ HTML 報告：點開能看每個 case 的 input/output/expected/pass
- ❌ CI 整合：未 wire 進 GitHub Actions（既有 `eval-weekly.yml` 仍跑舊 runner）
- ❌ Discord 通知：未串 `format_report.py`
- ❌ A/B model 在 CI 跑：可手動本地跑，未自動化

## 後續步驟（如果 PoC 結果接受）

1. 在 `.github/workflows/` 加 `eval-promptfoo.yml`（並存）
2. 寫 `format_promptfoo_report.py` 把 result.json 轉 Discord embed
3. 確認穩定後 retire `runner.py`，保留 scorer.py + cases.jsonl

## 已知 issue

- 每個 case 都會 spawn 一個 Python subprocess（Promptfoo 設計）→ startup overhead
  比 in-process 慢。對 38 case 影響不大（~30 秒），對 500+ case 要看
- `_parser` 全域快取在 subprocess 模式下失效（每次新 subprocess 重建 client）

## 移植 / 取消這個 PoC

整個 PoC 隔離在 `discord-monitor/eval/promptfoo/`，刪除這個資料夾即可完整移除，
**不影響既有 runner.py / scorer.py / cases.jsonl / eval-weekly.yml**。
