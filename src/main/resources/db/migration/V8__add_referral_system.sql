-- V8: 推薦系統 — user_exchange_referral_links 表
-- 記錄用戶的交易所推薦綁定狀態 (Single Source of Truth)
-- 狀態: NOT_STARTED → PENDING → VERIFIED

CREATE TABLE user_exchange_referral_links (
    id              BIGSERIAL       PRIMARY KEY,
    user_id         VARCHAR(64)     NOT NULL,
    exchange        VARCHAR(32)     NOT NULL DEFAULT 'BINANCE',
    exchange_uid    VARCHAR(64),
    status          VARCHAR(32)     NOT NULL DEFAULT 'NOT_STARTED',
    verified_at     TIMESTAMP,
    admin_notes     TEXT,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,

    -- 每用戶每交易所只一筆
    CONSTRAINT uq_uerl_user_exchange UNIQUE (user_id, exchange),

    -- 外鍵
    CONSTRAINT fk_uerl_user FOREIGN KEY (user_id) REFERENCES users(user_id)
);

-- UID 不可重複綁定（排除 NULL）
CREATE UNIQUE INDEX uq_uerl_exchange_uid
    ON user_exchange_referral_links (exchange, exchange_uid)
    WHERE exchange_uid IS NOT NULL;

-- 查詢索引
CREATE INDEX idx_uerl_status ON user_exchange_referral_links (status);
CREATE INDEX idx_uerl_user   ON user_exchange_referral_links (user_id);
