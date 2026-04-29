package cn.daenx.myadmin.server.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MessageTableInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public MessageTableInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureTable();
            ensureColumn("msg_id", "ALTER TABLE t_message ADD COLUMN msg_id VARCHAR(128) NULL COMMENT 'message id' AFTER time_stamp");
            ensureColumn("msg_type", "ALTER TABLE t_message ADD COLUMN msg_type INT NULL COMMENT 'message type' AFTER msg_id");
            ensureColumn("msg_source", "ALTER TABLE t_message ADD COLUMN msg_source INT NULL COMMENT 'message source' AFTER msg_type");
            ensureColumn("source", "ALTER TABLE t_message ADD COLUMN source VARCHAR(32) NULL COMMENT 'ingress channel' AFTER msg_source");
            ensureColumn("from_wxid", "ALTER TABLE t_message ADD COLUMN from_wxid VARCHAR(128) NULL COMMENT 'group or peer wxid' AFTER source");
            ensureColumn("sender_wxid", "ALTER TABLE t_message ADD COLUMN sender_wxid VARCHAR(128) NULL COMMENT 'sender wxid' AFTER from_wxid");
            ensureColumn("signature", "ALTER TABLE t_message ADD COLUMN signature VARCHAR(255) NULL COMMENT 'message signature' AFTER sender_wxid");
            ensureColumn("fingerprint", "ALTER TABLE t_message ADD COLUMN fingerprint CHAR(64) NULL COMMENT 'message fingerprint' AFTER signature");
            ensureColumn("raw_json", "ALTER TABLE t_message ADD COLUMN raw_json LONGTEXT NULL COMMENT 'raw payload json' AFTER fingerprint");
            ensureColumn("received_at", "ALTER TABLE t_message ADD COLUMN received_at DATETIME NULL COMMENT 'message time' AFTER raw_json");
            ensureColumn("created_at", "ALTER TABLE t_message ADD COLUMN created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time' AFTER received_at");

            ensureIndex("idx_t_message_time_stamp", "CREATE INDEX idx_t_message_time_stamp ON t_message (time_stamp)");
            ensureIndex("idx_t_message_msg_id", "CREATE INDEX idx_t_message_msg_id ON t_message (msg_id)");
            ensureIndex("idx_t_message_received_at", "CREATE INDEX idx_t_message_received_at ON t_message (received_at)");
            ensureIndex("idx_t_message_fingerprint", "CREATE INDEX idx_t_message_fingerprint ON t_message (fingerprint)");

            normalizeBlankToNull("msg_id");
            normalizeBlankToNull("fingerprint");
            ensureUniqueIndex(
                    "uk_t_message_source_msg_id",
                    "CREATE UNIQUE INDEX uk_t_message_source_msg_id ON t_message (source, msg_id)",
                    "SELECT COUNT(*) FROM (" +
                            "SELECT source, msg_id FROM t_message " +
                            "WHERE msg_id IS NOT NULL AND TRIM(msg_id) <> '' " +
                            "GROUP BY source, msg_id HAVING COUNT(*) > 1" +
                            ") dup"
            );
            ensureUniqueIndex(
                    "uk_t_message_source_fingerprint",
                    "CREATE UNIQUE INDEX uk_t_message_source_fingerprint ON t_message (source, fingerprint)",
                    "SELECT COUNT(*) FROM (" +
                            "SELECT source, fingerprint FROM t_message " +
                            "WHERE fingerprint IS NOT NULL AND TRIM(fingerprint) <> '' " +
                            "GROUP BY source, fingerprint HAVING COUNT(*) > 1" +
                            ") dup"
            );
        } catch (Exception e) {
            log.warn("skip t_message schema init because database is unavailable at startup: {}", e.getMessage());
        }
    }

    private void ensureTable() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS t_message (" +
                        "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                        "msg LONGTEXT NULL COMMENT 'message body'," +
                        "time_stamp VARCHAR(50) NULL COMMENT 'message timestamp'," +
                        "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time'" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='raw wechat messages'"
        );
    }

    private void ensureColumn(String columnName, String ddl) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_message' AND COLUMN_NAME = ?",
                Integer.class,
                columnName
        );
        if (count == null || count == 0) {
            jdbcTemplate.execute(ddl);
            log.info("t_message added column {}", columnName);
        }
    }

    private void ensureIndex(String indexName, String ddl) {
        if (indexExists(indexName)) {
            return;
        }
        jdbcTemplate.execute(ddl);
        log.info("t_message added index {}", indexName);
    }

    private void ensureUniqueIndex(String indexName, String ddl, String duplicateCheckSql) {
        if (indexExists(indexName)) {
            return;
        }
        Integer duplicateGroups = jdbcTemplate.queryForObject(duplicateCheckSql, Integer.class);
        if (duplicateGroups != null && duplicateGroups > 0) {
            log.warn("t_message skipped unique index {} because {} duplicate groups remain", indexName, duplicateGroups);
            return;
        }
        jdbcTemplate.execute(ddl);
        log.info("t_message added unique index {}", indexName);
    }

    private boolean indexExists(String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_message' AND INDEX_NAME = ?",
                Integer.class,
                indexName
        );
        return count != null && count > 0;
    }

    private void normalizeBlankToNull(String columnName) {
        int updated = jdbcTemplate.update(
                "UPDATE t_message SET " + columnName + " = NULL " +
                        "WHERE " + columnName + " IS NOT NULL AND TRIM(" + columnName + ") = ''"
        );
        if (updated > 0) {
            log.info("t_message normalized {} blank values in {}", updated, columnName);
        }
    }
}
