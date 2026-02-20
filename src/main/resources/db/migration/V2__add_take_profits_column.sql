-- =============================================
-- V2__add_take_profits_column.sql
-- 新增止盈目標欄位到 trades 表
-- =============================================

ALTER TABLE trades ADD COLUMN IF NOT EXISTS take_profits TEXT;

-- COMMENT: 存放 JSON 格式的止盈目標，如 {"targets": [68400.0, 66700.0]}
