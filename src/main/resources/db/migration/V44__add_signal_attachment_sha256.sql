-- Add attachment_sha256 to signals table for image-signal audit trail.
-- Python sends source.attachment.sha256 when an image triggered the signal;
-- without this column we lose the ability to trace "which image triggered this trade".
ALTER TABLE signals ADD COLUMN attachment_sha256 VARCHAR(64);
CREATE INDEX idx_sig_attachment_sha256 ON signals(attachment_sha256) WHERE attachment_sha256 IS NOT NULL;
