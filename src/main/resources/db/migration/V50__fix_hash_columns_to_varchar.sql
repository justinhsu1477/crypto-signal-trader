-- 修正 V47/V48/V49 把 SHA-256 hex hash 欄位定義成 CHAR(16) 的問題。
--
-- 背景：
--   V47.before_hash / V47.after_hash / V48.custom_prompt_sha256 / V49.custom_prompt_sha256
--   都用 CHAR(16)，在 PostgreSQL = bpchar，會做 trailing space padding。
--   但 JPA 端 @Column(length=16) 預設對應 VARCHAR(16)。
--   Hibernate ddl-auto=validate 比對失敗 → Spring boot 起不來 → 5/15 prod 5xx fail loop。
--
-- 修法：4 欄全部 ALTER 成 VARCHAR(16)。
--   SHA-256 hex 永遠是固定 16 字元，CHAR padding 對我們沒實質好處，且 Spring/JPA 慣例是 VARCHAR。
--   USING TRIM(...) 移除 CHAR padding 殘留的尾空白（雖然 4 欄在 V50 應用時都還沒實際資料）。
--
-- 冪等：DO block + IF EXISTS udt_name='bpchar' 檢查。
--   - prod 第一次跑：手動 ALTER 已先執行過 → IF 不命中 → no-op，但 Flyway 還是記為 applied
--   - 新環境（dev replica / staging）：仍是 bpchar → 真的執行 ALTER
--   - 後續重跑：udt 已是 varchar → no-op

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'admin_audit_log'
          AND column_name = 'before_hash'
          AND udt_name = 'bpchar'
    ) THEN
        ALTER TABLE admin_audit_log
            ALTER COLUMN before_hash TYPE VARCHAR(16) USING TRIM(before_hash);
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'admin_audit_log'
          AND column_name = 'after_hash'
          AND udt_name = 'bpchar'
    ) THEN
        ALTER TABLE admin_audit_log
            ALTER COLUMN after_hash TYPE VARCHAR(16) USING TRIM(after_hash);
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'signal_sources'
          AND column_name = 'custom_prompt_sha256'
          AND udt_name = 'bpchar'
    ) THEN
        ALTER TABLE signal_sources
            ALTER COLUMN custom_prompt_sha256 TYPE VARCHAR(16) USING TRIM(custom_prompt_sha256);
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'signals'
          AND column_name = 'custom_prompt_sha256'
          AND udt_name = 'bpchar'
    ) THEN
        ALTER TABLE signals
            ALTER COLUMN custom_prompt_sha256 TYPE VARCHAR(16) USING TRIM(custom_prompt_sha256);
    END IF;
END $$;
