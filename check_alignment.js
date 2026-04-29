const mysql = require('mysql2/promise');

const DB_CONFIG = {
  host: '10.8.0.110',
  port: 3306,
  user: 'root',
  password: 'root',
  database: 'wechat_msg'
};

async function checkAlignment() {
  const connection = await mysql.createConnection(DB_CONFIG);

  console.log('='.repeat(60));
  console.log('🔍 20:22-21:30 消息对齐检查');
  console.log('='.repeat(60));

  // 统计这段时间的消息
  const [stats] = await connection.execute(`
    SELECT
      COUNT(*) as total,
      COUNT(DISTINCT fingerprint) as unique_msg,
      MIN(received_at) as first_msg,
      MAX(received_at) as last_msg
    FROM t_message
    WHERE DATE(received_at) = '2026-03-31'
      AND TIME(received_at) BETWEEN '20:22:00' AND '21:30:00'
  `);

  console.log('\n📊 基本统计:');
  console.log(`总消息数: ${stats[0].total}`);
  console.log(`去重后: ${stats[0].unique_msg}`);
  console.log(`重复消息: ${stats[0].total - stats[0].unique_msg}`);
  console.log(`时间范围: ${stats[0].first_msg} ~ ${stats[0].last_msg}`);

  // 检查是否有连续的时间间隔
  const [gaps] = await connection.execute(`
    SELECT
      DATE_FORMAT(t1.received_at, '%H:%i:%s') as time1,
      DATE_FORMAT(t2.received_at, '%H:%i:%s') as time2,
      TIMESTAMPDIFF(SECOND, t1.received_at, t2.received_at) as gap_seconds
    FROM t_message t1
    JOIN t_message t2 ON t2.id = (
      SELECT MIN(id) FROM t_message
      WHERE received_at > t1.received_at
        AND DATE(received_at) = '2026-03-31'
        AND TIME(received_at) BETWEEN '20:22:00' AND '21:30:00'
    )
    WHERE DATE(t1.received_at) = '2026-03-31'
      AND TIME(t1.received_at) BETWEEN '20:22:00' AND '21:30:00'
      AND TIMESTAMPDIFF(SECOND, t1.received_at, t2.received_at) > 60
    ORDER BY gap_seconds DESC
    LIMIT 10
  `);

  console.log('\n⚠️ 超过60秒的消息间隔 (可能丢失):');
  if (gaps.length === 0) {
    console.log('✓ 未发现明显间隔，消息连续');
  } else {
    gaps.forEach(row => {
      console.log(`${row.time1} → ${row.time2}: 间隔 ${row.gap_seconds}秒`);
    });
  }

  // 检查订单处理情况
  const [orderStats] = await connection.execute(`
    SELECT
      COUNT(*) as raw_count,
      SUM(CASE WHEN id IN (
        SELECT raw_id FROM t_order_parse_batch WHERE parse_status = 1
      ) THEN 1 ELSE 0 END) as success_count,
      SUM(CASE WHEN id IN (
        SELECT raw_id FROM t_order_parse_batch WHERE parse_status = 2
      ) THEN 1 ELSE 0 END) as failed_count
    FROM t_order_raw
    WHERE DATE(received_at) = '2026-03-31'
      AND TIME(received_at) BETWEEN '20:22:00' AND '21:30:00'
  `);

  console.log('\n📦 订单处理情况:');
  console.log(`订单总数: ${orderStats[0].raw_count}`);
  console.log(`解析成功: ${orderStats[0].success_count}`);
  console.log(`解析失败: ${orderStats[0].failed_count}`);
  console.log(`成功率: ${(orderStats[0].success_count / orderStats[0].raw_count * 100).toFixed(1)}%`);

  await connection.end();
  console.log('\n✓ 检查完成');
}

checkAlignment().catch(console.error);
