-- V10: 新增動態風控百分比欄位
-- daily_loss_percent: 每日虧損上限 = SOD 餘額 × 此百分比（null = 使用全局 RiskConfig）
-- max_position_percent: 單筆倉位上限 = 即時餘額 × 此百分比（null = 使用全局 RiskConfig）

ALTER TABLE user_trade_settings
    ADD COLUMN IF NOT EXISTS daily_loss_percent DOUBLE PRECISION;

ALTER TABLE user_trade_settings
    ADD COLUMN IF NOT EXISTS max_position_percent DOUBLE PRECISION;
