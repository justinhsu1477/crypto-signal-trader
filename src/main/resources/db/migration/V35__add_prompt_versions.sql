-- Prompt 版本管理：DB 存儲 AI prompt 版本歷史，Admin 可切換/回滾

CREATE TABLE IF NOT EXISTS prompt_versions (
    id          BIGSERIAL PRIMARY KEY,
    version     INT NOT NULL UNIQUE,
    content     TEXT NOT NULL,
    description VARCHAR(500),
    is_active   BOOLEAN NOT NULL DEFAULT FALSE,
    token_count INT,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_prompt_versions_active ON prompt_versions(is_active);

-- 交易追溯：記錄每筆交易用的 prompt 版本
ALTER TABLE trades ADD COLUMN IF NOT EXISTS prompt_version INT;
