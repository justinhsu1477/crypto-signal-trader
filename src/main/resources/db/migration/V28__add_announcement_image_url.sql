-- 公告新增圖片 URL 欄位（可選，用於 Discord Embed image / LINE image message）
ALTER TABLE announcements ADD COLUMN IF NOT EXISTS image_url VARCHAR(500);
