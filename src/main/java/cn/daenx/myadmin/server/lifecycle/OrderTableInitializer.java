package cn.daenx.myadmin.server.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class OrderTableInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public OrderTableInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureIndex("t_order_raw", "idx_t_order_raw_received_id",
                    "CREATE INDEX idx_t_order_raw_received_id ON t_order_raw (received_at, id)");
            ensureIndex("t_order_raw", "idx_t_order_raw_content_window",
                    "CREATE INDEX idx_t_order_raw_content_window ON t_order_raw (source, from_wxid, sender_wxid, received_at)");
            ensureIndex("t_order_raw", "idx_t_order_raw_msg_id",
                    "CREATE INDEX idx_t_order_raw_msg_id ON t_order_raw (msg_id)");
            ensureIndex("t_order_raw", "idx_t_order_raw_fingerprint",
                    "CREATE INDEX idx_t_order_raw_fingerprint ON t_order_raw (fingerprint)");
            ensureUniqueIndex("t_order_raw", "uk_t_order_raw_fingerprint",
                    "CREATE UNIQUE INDEX uk_t_order_raw_fingerprint ON t_order_raw (fingerprint)",
                    "SELECT COUNT(*) FROM (" +
                            "SELECT fingerprint FROM t_order_raw " +
                            "WHERE fingerprint IS NOT NULL AND TRIM(fingerprint) <> '' " +
                            "GROUP BY fingerprint HAVING COUNT(*) > 1" +
                            ") dup");

            ensureIndex("t_order_parse_batch", "idx_t_order_parse_batch_raw_id_id",
                    "CREATE INDEX idx_t_order_parse_batch_raw_id_id ON t_order_parse_batch (raw_id, id)");
            ensureIndex("t_order_parse_batch", "idx_t_order_parse_batch_raw_effective",
                    "CREATE INDEX idx_t_order_parse_batch_raw_effective ON t_order_parse_batch (raw_id, is_effective, id)");

            ensureIndex("t_order_item", "idx_t_order_item_raw_batch",
                    "CREATE INDEX idx_t_order_item_raw_batch ON t_order_item (raw_id, batch_id)");
            ensureIndex("t_order_item", "idx_t_order_item_batch",
                    "CREATE INDEX idx_t_order_item_batch ON t_order_item (batch_id)");
            ensureIndex("t_order_item_number", "idx_t_order_item_number_item",
                    "CREATE INDEX idx_t_order_item_number_item ON t_order_item_number (item_id)");
        } catch (Exception e) {
            log.warn("skip order schema init because database is unavailable at startup: {}", e.getMessage());
        }
    }

    private void ensureIndex(String tableName, String indexName, String ddl) {
        if (!tableExists(tableName) || indexExists(tableName, indexName)) {
            return;
        }
        jdbcTemplate.execute(ddl);
        log.info("{} added index {}", tableName, indexName);
    }

    private void ensureUniqueIndex(String tableName, String indexName, String ddl, String duplicateCheckSql) {
        if (!tableExists(tableName) || indexExists(tableName, indexName)) {
            return;
        }
        Integer duplicateGroups = jdbcTemplate.queryForObject(duplicateCheckSql, Integer.class);
        if (duplicateGroups != null && duplicateGroups > 0) {
            log.warn("{} skipped unique index {} because {} duplicate groups remain", tableName, indexName, duplicateGroups);
            return;
        }
        jdbcTemplate.execute(ddl);
        log.info("{} added unique index {}", tableName, indexName);
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.TABLES " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                Integer.class,
                tableName
        );
        return count != null && count > 0;
    }

    private boolean indexExists(String tableName, String indexName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.STATISTICS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?",
                Integer.class,
                tableName,
                indexName
        );
        return count != null && count > 0;
    }
}
