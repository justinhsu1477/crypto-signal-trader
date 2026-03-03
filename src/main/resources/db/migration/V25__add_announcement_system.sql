-- =============================================
-- V25: 公告廣播系統
-- 1. announcements: 公告主表
-- 2. announcement_read_tracking: 用戶已讀追蹤
-- =============================================

CREATE TABLE IF NOT EXISTS announcements (
    id              BIGSERIAL PRIMARY KEY,
    title           VARCHAR(200) NOT NULL,
    content         TEXT NOT NULL,
    category        VARCHAR(30) NOT NULL DEFAULT 'GENERAL',
    priority        VARCHAR(10) NOT NULL DEFAULT 'NORMAL',
    channels        VARCHAR(100) NOT NULL DEFAULT 'ALL',
    status          VARCHAR(15) NOT NULL DEFAULT 'DRAFT',
    published_at    TIMESTAMP,
    created_by      VARCHAR(36) NOT NULL REFERENCES users(user_id),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ann_status ON announcements(status);
CREATE INDEX IF NOT EXISTS idx_ann_published_at ON announcements(published_at);

CREATE TABLE IF NOT EXISTS announcement_read_tracking (
    id                BIGSERIAL PRIMARY KEY,
    announcement_id   BIGINT NOT NULL REFERENCES announcements(id) ON DELETE CASCADE,
    user_id           VARCHAR(36) NOT NULL REFERENCES users(user_id),
    read_at           TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(announcement_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_art_user_id ON announcement_read_tracking(user_id);
