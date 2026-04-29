USE wechat_msg;

CREATE TABLE IF NOT EXISTS t_message_duplicate_backup LIKE t_message;

INSERT IGNORE INTO t_message_duplicate_backup
SELECT m.*
FROM t_message m
JOIN (
    SELECT t1.id
    FROM t_message t1
    JOIN t_message t2
      ON t1.source <=> t2.source
     AND t1.msg_id = t2.msg_id
     AND t1.id > t2.id
    WHERE t1.msg_id IS NOT NULL
      AND TRIM(t1.msg_id) <> ''

    UNION

    SELECT t1.id
    FROM t_message t1
    JOIN t_message t2
      ON t1.source <=> t2.source
     AND t1.fingerprint = t2.fingerprint
     AND t1.id > t2.id
    WHERE (t1.msg_id IS NULL OR TRIM(t1.msg_id) = '')
      AND (t2.msg_id IS NULL OR TRIM(t2.msg_id) = '')
      AND t1.fingerprint IS NOT NULL
      AND TRIM(t1.fingerprint) <> ''
) dup ON dup.id = m.id;

DELETE m
FROM t_message m
JOIN (
    SELECT t1.id
    FROM t_message t1
    JOIN t_message t2
      ON t1.source <=> t2.source
     AND t1.msg_id = t2.msg_id
     AND t1.id > t2.id
    WHERE t1.msg_id IS NOT NULL
      AND TRIM(t1.msg_id) <> ''

    UNION

    SELECT t1.id
    FROM t_message t1
    JOIN t_message t2
      ON t1.source <=> t2.source
     AND t1.fingerprint = t2.fingerprint
     AND t1.id > t2.id
    WHERE (t1.msg_id IS NULL OR TRIM(t1.msg_id) = '')
      AND (t2.msg_id IS NULL OR TRIM(t2.msg_id) = '')
      AND t1.fingerprint IS NOT NULL
      AND TRIM(t1.fingerprint) <> ''
) dup ON dup.id = m.id;

UPDATE t_message
SET msg_id = NULL
WHERE msg_id IS NOT NULL
  AND TRIM(msg_id) = '';

UPDATE t_message
SET fingerprint = NULL
WHERE fingerprint IS NOT NULL
  AND TRIM(fingerprint) = '';

CREATE UNIQUE INDEX uk_t_message_source_msg_id ON t_message (source, msg_id);
CREATE UNIQUE INDEX uk_t_message_source_fingerprint ON t_message (source, fingerprint);
