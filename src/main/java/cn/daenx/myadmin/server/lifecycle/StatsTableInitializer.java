package cn.daenx.myadmin.server.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class StatsTableInitializer implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;

    public StatsTableInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ensureNumberStatsTable();
            ensureNumberStatsIndex(
                    "uk_t_number_stats_scope",
                    "CREATE UNIQUE INDEX uk_t_number_stats_scope ON t_number_stats " +
                            "(lottery_category, game_type, play_type, issue_key, number_zone, number)"
            );
            ensureNumberStatsIndex(
                    "idx_t_number_stats_query",
                    "CREATE INDEX idx_t_number_stats_query ON t_number_stats " +
                            "(lottery_category, game_type, issue_key, play_type, number_zone, number)"
            );
            ensureNumberStatsIndex(
                    "idx_t_number_stats_issue",
                    "CREATE INDEX idx_t_number_stats_issue ON t_number_stats (issue_key)"
            );

            if (tableExists("t_ai_parse_result")) {
                ensureAiParseResultIndex(
                        "idx_t_ai_parse_result_issue_scope",
                        "CREATE INDEX idx_t_ai_parse_result_issue_scope ON t_ai_parse_result " +
                                "(issue_key, category, game, status, valid, raw_id, batch_id)"
                );
                ensureAiParseResultIndex(
                        "idx_t_ai_parse_result_raw_batch",
                        "CREATE INDEX idx_t_ai_parse_result_raw_batch ON t_ai_parse_result (raw_id, batch_id)"
                );
            }
        } catch (Exception e) {
            log.warn("skip stats schema init because database is unavailable at startup: {}", e.getMessage());
        }
    }

    private void ensureNumberStatsTable() {
        jdbcTemplate.execute(
                "CREATE TABLE IF NOT EXISTS t_number_stats (" +
                        "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                        "lottery_category VARCHAR(32) NOT NULL COMMENT '彩种类别'," +
                        "game_type VARCHAR(32) NOT NULL COMMENT '游戏类型'," +
                        "play_type VARCHAR(32) NOT NULL COMMENT '玩法'," +
                        "issue_key VARCHAR(32) NOT NULL COMMENT '期次'," +
                        "number_zone VARCHAR(32) NOT NULL COMMENT '号码区域'," +
                        "number VARCHAR(64) NOT NULL COMMENT '号码'," +
                        "order_count INT NOT NULL DEFAULT 0 COMMENT '注数'," +
                        "sum_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '金额'," +
                        "last_updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) " +
                        "ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最后更新时间'" +
                        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='号码聚合统计表'"
        );
    }

    private void ensureNumberStatsIndex(String indexName, String ddl) {
        if (indexExists("t_number_stats", indexName)) {
            return;
        }
        jdbcTemplate.execute(ddl);
        log.info("t_number_stats added index {}", indexName);
    }

    private void ensureAiParseResultIndex(String indexName, String ddl) {
        if (indexExists("t_ai_parse_result", indexName)) {
            return;
        }
        jdbcTemplate.execute(ddl);
        log.info("t_ai_parse_result added index {}", indexName);
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
