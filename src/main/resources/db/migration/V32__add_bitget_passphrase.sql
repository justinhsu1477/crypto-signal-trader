-- Bitget 交易所需要額外的 passphrase 認證（Binance/Bybit 不需要）
-- 此欄位可為空，只有 exchange=BITGET 時才會有值
ALTER TABLE user_api_keys ADD COLUMN encrypted_passphrase VARCHAR(512);
