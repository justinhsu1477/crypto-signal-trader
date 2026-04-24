-- W6a: Chatbot Prompt 資料化
-- 獨立表（不動現有 prompt_versions，後者給 Python Monitor 訊號解析用）
-- 支援多種 prompt type（system_user / system_admin / intent_classifier / query_rewrite / ...）

CREATE TABLE IF NOT EXISTS chatbot_prompts (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    version INT NOT NULL,
    content TEXT NOT NULL,
    description VARCHAR(500),
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_chatbot_prompt_name_version UNIQUE (name, version)
);

-- 對每個 name 來說只會同時有一個 active 版本；用部分索引加速「取當前 active」
CREATE UNIQUE INDEX IF NOT EXISTS idx_chatbot_prompt_active_per_name
    ON chatbot_prompts (name)
    WHERE is_active = TRUE;
