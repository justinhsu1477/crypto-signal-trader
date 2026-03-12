-- V31: 訊號來源新增路由模式（GLOBAL = 全員廣播, ASSIGNED = 僅綁定用戶）
ALTER TABLE signal_sources ADD COLUMN IF NOT EXISTS routing_mode VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED';

COMMENT ON COLUMN signal_sources.routing_mode IS 'GLOBAL = 廣播給所有用戶; ASSIGNED = 只廣播給綁定用戶';
