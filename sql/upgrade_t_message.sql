USE wechat_msg;

ALTER TABLE t_message ADD COLUMN IF NOT EXISTS msg_id VARCHAR(128) NULL COMMENT 'wechat message id' AFTER time_stamp;
ALTER TABLE t_message ADD COLUMN IF NOT EXISTS msg_type INT NULL COMMENT 'wechat message type' AFTER msg_id;
ALTER TABLE t_message ADD COLUMN IF NOT EXISTS msg_source INT NULL COMMENT 'sender source flag' AFTER msg_type;
ALTER TABLE t_message ADD COLUMN IF NOT EXISTS source VARCHAR(32) NULL COMMENT 'ingress channel' AFTER msg_source;
ALTER TABLE t_message ADD COLUMN IF NOT EXISTS from_wxid VARCHAR(128) NULL COMMENT 'group or peer wxid' AFTER source;
ALTER TABLE t_message ADD COLUMN IF NOT EXISTS sender_wxid VARCHAR(128) NULL COMMENT 'sender wxid' AFTER from_wxid;
ALTER TABLE t_message ADD COLUMN IF NOT EXISTS signature VARCHAR(255) NULL COMMENT 'message signature' AFTER sender_wxid;
ALTER TABLE t_message ADD COLUMN IF NOT EXISTS fingerprint CHAR(64) NULL COMMENT 'message fingerprint' AFTER signature;
ALTER TABLE t_message ADD COLUMN IF NOT EXISTS raw_json LONGTEXT NULL COMMENT 'raw payload' AFTER fingerprint;
ALTER TABLE t_message ADD COLUMN IF NOT EXISTS received_at DATETIME NULL COMMENT 'message receive time' AFTER raw_json;
ALTER TABLE t_message ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time' AFTER received_at;

CREATE INDEX IF NOT EXISTS idx_t_message_time_stamp ON t_message (time_stamp);
CREATE INDEX IF NOT EXISTS idx_t_message_msg_id ON t_message (msg_id);
CREATE INDEX IF NOT EXISTS idx_t_message_received_at ON t_message (received_at);
CREATE INDEX IF NOT EXISTS idx_t_message_fingerprint ON t_message (fingerprint);

UPDATE t_message SET msg_id = NULL WHERE msg_id IS NOT NULL AND TRIM(msg_id) = '';
UPDATE t_message SET fingerprint = NULL WHERE fingerprint IS NOT NULL AND TRIM(fingerprint) = '';

-- If historical duplicates exist, run sql/dedupe_t_message.sql before these unique indexes.
CREATE UNIQUE INDEX IF NOT EXISTS uk_t_message_source_msg_id ON t_message (source, msg_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_t_message_source_fingerprint ON t_message (source, fingerprint);
