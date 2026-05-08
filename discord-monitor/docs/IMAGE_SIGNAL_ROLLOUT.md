# Image Signal 推出計畫

## 三階段上線

### Phase 0: 觀測（已上線 Task 3）
- 設定保持預設（`image_signal.enabled: false`）
- 觀察 log 中的 `MISSED_IMAGE_ONLY` 出現頻率
- 跑 1 週，記錄每天漏多少純圖訊號

```bash
# 在 VM 或本地 grep log
grep MISSED_IMAGE_ONLY /Users/justinhsu/Desktop/sideproject/crypto-signal-trader/discord-monitor/discord-monitor.log | wc -l
```

### Phase 1: Shadow Mode（解析但不下單）
config.yml:
```yaml
image_signal:
  enabled: true
  dry_run: true
```

- image path 跑完整解析、log 出 `[IMAGE DRY RUN]` 訊息
- **不送 Java、不下單**
- 跑 3-5 天，比對：
  - 解析結果是否與人工肉眼判讀一致
  - 是否誤觸（K線圖、閒聊圖被當訊號）
  - Gemini token 成本是否合理

```bash
# 看 dry-run 的 parse 結果
grep "IMAGE DRY RUN" discord-monitor.log
# 看 parse 失敗
grep "image path: parse failed" discord-monitor.log
```

### Phase 2: Canary（小流量）
- 確認 dry-run 5 天結果準確 → 改 config:
```yaml
image_signal:
  enabled: true
  dry_run: false
```
- 觀察前 24 小時，確認：
  - Java L1 (`source.message_id` 永久 dedup) 沒被誤觸
  - send_trade 200 OK
  - 沒有重複進場（看 trades 表 + Discord admin 通知）

### Phase 3: 正式
- 跑滿一週無事件 → 視為穩定
- 加 metrics（後續任務）

## Rollback 程序

**任何階段出問題：**

1. 改 `config.yml`：
   ```yaml
   image_signal:
     enabled: false
   ```
2. **gRPC 熱推會自動下發**（如果有 Java Admin UI 控制 image_signal config — 目前沒做這層，需手動改 + 重啟 Python）
3. 或直接重啟 Python：
   ```bash
   pkill -f "python -m src.main"
   cd /Users/justinhsu/Desktop/sideproject/crypto-signal-trader/discord-monitor
   python -m src.main --config config.yml
   ```

## 監控建議

每天看的 log line（grep 友善）：
- `MISSED_IMAGE_ONLY` — 純圖訊息漏失（Phase 0 用）
- `image path triggered` — image path 啟動次數
- `image path: parse failed` — Vision LLM 解析失敗
- `image path: symbol .* not in allowed_symbols` — 非 BTC 訊號被擋
- `[IMAGE DRY RUN]` — shadow mode 應該送但沒送的訊號
- `image path: send_trade result` — 真的送出 Java 的結果

## 已知限制

- **CDP JS hook 變更需要重啟 Discord + 重啟 Python**（一次性）
- **Gemini multimodal 沒有 prompt caching**（每張圖約 ~$0.0005-0.002）
- **多圖訊息只處理第一張**（陳哥訊號通常一張，足夠）
- **無 image hash 跨重啟去重**（依賴 Java L1+L2，5 分鐘窗口外可能重送）

## 不在範圍內（後續可考慮）

- 加入 OCR 預過濾省 token（目前直接打 Gemini）
- Image hash 持久化跨重啟去重
- Java 端新增 `image_hash` 欄位 + UNIQUE constraint
- 修掉 `signals.source_message_id` race（改 UNIQUE constraint）
- 修掉 `trade_action_detector` 的 `BTCUSDT` 寫死問題
