# 資料庫備份策略

## 環境

| 項目 | 值 |
|------|------|
| 資料庫 | Neon Serverless Postgres |
| Project ID | `<neon-project-id>` |
| Database | `trading` |
| PostgreSQL 版本 | 16 |
| 區域 | AWS us-east-1 |

---

## 1. Neon 內建 PITR（Point-in-Time Recovery）

Neon 內建 WAL-based PITR，**無需額外設定**。

| 項目 | Free Tier | Pro / Scale |
|------|-----------|-------------|
| 保留天數 | 7 天 | 7~30 天（可調整） |
| 恢復粒度 | 任意時間點 | 任意時間點 |
| RPO | ~0（WAL 即時寫入） | ~0 |
| RTO | 分鐘級（建立 Branch） | 分鐘級 |

### 恢復方式

透過 Neon Console 或 CLI 建立 Branch 指定時間點：

```bash
# CLI 範例：恢復到特定時間點
neonctl branches create \
  --project-id <neon-project-id> \
  --name recovery-$(date +%Y%m%d-%H%M) \
  --parent main \
  --set-as-primary \
  --restore-to "2026-03-04T10:30:00Z"
```

或在 Neon Console → Branches → Restore → 選擇時間點。

---

## 2. 定期 pg_dump（外部備份）

Neon PITR 只保留 7~30 天，超過需要額外備份。

### 備份腳本

```bash
#!/bin/bash
# scripts/backup-db.sh
# 定期執行 pg_dump 到本地或 S3

set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-./backups}"
DATE=$(date +%Y%m%d_%H%M%S)
FILENAME="trading_backup_${DATE}.sql.gz"

mkdir -p "$BACKUP_DIR"

# 使用 Neon connection string（從環境變數讀取）
pg_dump "$DATABASE_URL" \
  --no-owner \
  --no-privileges \
  --clean \
  --if-exists \
  | gzip > "${BACKUP_DIR}/${FILENAME}"

echo "Backup completed: ${BACKUP_DIR}/${FILENAME}"

# 清理 30 天前的備份
find "$BACKUP_DIR" -name "trading_backup_*.sql.gz" -mtime +30 -delete
echo "Old backups cleaned up"
```

### 排程（crontab）

```bash
# 每日凌晨 3:00（台灣時間）備份
0 3 * * * DATABASE_URL="postgresql://..." /path/to/scripts/backup-db.sh >> /var/log/db-backup.log 2>&1
```

---

## 3. RPO / RTO 目標

| 指標 | 目標 | 實際 |
|------|------|------|
| **RPO**（資料遺失容忍） | < 1 分鐘 | ~0（Neon WAL 即時） |
| **RTO**（恢復時間） | < 15 分鐘 | ~5 分鐘（Branch restore） |

---

## 4. 災難恢復流程

### 場景 A：誤刪資料 / 錯誤 Migration

1. 在 Neon Console 建立 Branch，指定誤操作前的時間點
2. 驗證恢復的 Branch 資料正確
3. 將恢復的 Branch 設為 Primary

### 場景 B：Neon 服務中斷

1. 從最近的 pg_dump 備份恢復到備用 PostgreSQL
2. 更新 `DATABASE_URL` 環境變數指向備用 DB
3. 重新部署應用

### 場景 C：資料損壞

1. 優先使用 Neon PITR 恢復到損壞前時間點
2. 若超出 PITR 保留期，使用 pg_dump 備份恢復

---

## 5. 驗證計畫

| 頻率 | 動作 |
|------|------|
| 每月 | 測試 Neon Branch restore（建立 → 驗證 → 刪除） |
| 每季 | 測試 pg_dump 完整恢復到空 DB |
| 每次 | 備份腳本執行後檢查檔案大小是否合理 |

---

## 6. 重要提醒

- **Neon 連線字串含密碼**，pg_dump 腳本的 `DATABASE_URL` 不要 commit 到 Git
- **HikariCP keepalive-time: 120s** — Neon serverless 會自動暫停閒置 compute，確保連線池配置正確
- **Flyway migration** — 所有 schema 變更都透過 Flyway 管理，備份時只需關注資料，schema 可從 migration 重建
