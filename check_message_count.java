import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import redis.clients.jedis.Jedis;

public class CheckMessageCount {

    private static final String DB_URL = "jdbc:mysql://10.8.0.110:3306/wechat_msg?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
    private static final String DB_USER = "root";
    private static final String DB_PASS = "root";

    private static final String REDIS_HOST = "10.8.0.110";
    private static final int REDIS_PORT = 6379;
    private static final String REDIS_PASS = "123456";
    private static final String QUEUE_NAME = "wechat_messages";

    public static void main(String[] args) {
        try {
            LocalDateTime today730PM = LocalDateTime.now()
                    .withHour(19)
                    .withMinute(30)
                    .withSecond(0)
                    .withNano(0);

            System.out.println("=== 消息数量检查 ===");
            System.out.println("时间范围: " + today730PM.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " 至今");
            System.out.println();

            long dbCount = getDbMessageCount(today730PM);
            long redisQueueCount = getRedisQueueLength();
            long totalProcessed = dbCount + redisQueueCount;

            System.out.println("已入库消息数: " + dbCount);
            System.out.println("Redis队列待处理: " + redisQueueCount);
            System.out.println("总计: " + totalProcessed);
            System.out.println();

            if (redisQueueCount > 0) {
                System.out.println("⚠ Redis队列中还有 " + redisQueueCount + " 条消息待处理");
            } else {
                System.out.println("✓ Redis队列已清空，所有消息已入库");
            }

            // 检查是否有重复消息
            long duplicateCount = getDuplicateCount(today730PM);
            if (duplicateCount > 0) {
                System.out.println("⚠ 发现 " + duplicateCount + " 条重复消息（相同fingerprint）");
            }

        } catch (Exception e) {
            System.err.println("检查失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static long getDbMessageCount(LocalDateTime since) throws SQLException {
        String sql = "SELECT COUNT(*) FROM t_message WHERE received_at >= ?";
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.valueOf(since));
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0;
    }

    private static long getRedisQueueLength() {
        try (Jedis jedis = new Jedis(REDIS_HOST, REDIS_PORT)) {
            jedis.auth(REDIS_PASS);
            return jedis.llen(QUEUE_NAME);
        }
    }

    private static long getDuplicateCount(LocalDateTime since) throws SQLException {
        String sql = "SELECT COUNT(*) - COUNT(DISTINCT fingerprint) FROM t_message WHERE received_at >= ?";
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setTimestamp(1, Timestamp.valueOf(since));
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0;
    }
}
