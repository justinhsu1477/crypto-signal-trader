-- RAG 向量知識庫：pgvector + knowledge_chunks 表

-- 啟用 pgvector 擴充（Neon 原生支援）
CREATE EXTENSION IF NOT EXISTS vector;

-- 知識庫 chunks 表
CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id SERIAL PRIMARY KEY,
    source VARCHAR(100) NOT NULL,                  -- 來源分類：faq, trade_guide, changelog, announcement
    title VARCHAR(300) NOT NULL,                   -- 段落標題
    content TEXT NOT NULL,                          -- 原始文字內容
    embedding vector(768),                         -- Gemini text-embedding-004 輸出維度
    metadata JSONB DEFAULT '{}',                   -- 額外 metadata（tags, version 等）
    enabled BOOLEAN DEFAULT TRUE,                  -- 是否啟用（軟刪除）
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 向量相似度搜尋索引（cosine distance）
-- lists 數量建議 = sqrt(rows)，初始 10 足夠
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_embedding
ON knowledge_chunks USING ivfflat (embedding vector_cosine_ops)
WITH (lists = 10);

-- 來源 + 啟用狀態索引（篩選用）
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_source ON knowledge_chunks(source);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_enabled ON knowledge_chunks(enabled);

COMMENT ON TABLE knowledge_chunks IS 'RAG 向量知識庫：儲存 FAQ、教學、公告等文本 chunks 及其 embedding 向量';
COMMENT ON COLUMN knowledge_chunks.embedding IS 'Gemini text-embedding-004 生成的 768 維向量';
COMMENT ON COLUMN knowledge_chunks.metadata IS '額外資訊如 tags、version、原始檔案名等';
