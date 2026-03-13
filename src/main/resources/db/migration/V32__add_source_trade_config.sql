-- Phase 1: per-source 交易模式 + 風險倍率 + AI 自訂指令
ALTER TABLE signal_sources ADD COLUMN IF NOT EXISTS trade_mode VARCHAR(20) DEFAULT 'AUTO' NOT NULL;
ALTER TABLE signal_sources ADD COLUMN IF NOT EXISTS risk_multiplier DOUBLE PRECISION DEFAULT 1.0 NOT NULL;
ALTER TABLE signal_sources ADD COLUMN IF NOT EXISTS custom_prompt TEXT DEFAULT '' NOT NULL;
