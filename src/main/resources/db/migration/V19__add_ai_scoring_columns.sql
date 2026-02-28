-- =============================================
-- V19: 新增 AI 信號評分欄位
-- 用途：廣播跟單時 AI 對信號打分（0-100），純記錄不影響交易
-- =============================================

ALTER TABLE trades ADD COLUMN ai_confidence INTEGER;
ALTER TABLE trades ADD COLUMN ai_reasoning VARCHAR(500);

COMMENT ON COLUMN trades.ai_confidence IS 'AI 信心分數 0-100（null=未評分）';
COMMENT ON COLUMN trades.ai_reasoning IS 'AI 評分理由（繁中，≤50字）';
