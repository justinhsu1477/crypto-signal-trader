-- Optimistic-lock version column for race-condition safety.
-- Two writers can concurrently update a discord_raw_messages row:
-- (a) Python POST /api/discord-messages updating parser_action
-- (b) Java SignalRecordService.linkDiscordRawMessage() setting signal_id
-- @Version makes JPA detect stale reads and reject the second write.
ALTER TABLE discord_raw_messages ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
