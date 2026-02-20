-- =============================================
-- V5: 補充 user_trade_settings 欄位
--
-- 新增 daily_loss_limit_usdt 和 dca_risk_multiplier
-- 兩欄都 nullable — null 表示使用全局 RiskConfig 預設值
-- =============================================

ALTER TABLE user_trade_settings
    ADD COLUMN IF NOT EXISTS daily_loss_limit_usdt DOUBLE PRECISION;

ALTER TABLE user_trade_settings
    ADD COLUMN IF NOT EXISTS dca_risk_multiplier DOUBLE PRECISION;
