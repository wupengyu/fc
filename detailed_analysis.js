const mysql = require('mysql2/promise');

const DB_CONFIG = {
  host: '10.8.0.110',
  port: 3306,
  user: 'root',
  password: 'root',
  database: 'wechat_msg'
};

async function detailedAnalysis() {
  const connection = await mysql.createConnection(DB_CONFIG);

  console.log('='.repeat(60));
  console.log('📋 详细消息分析报告');
  console.log('='.repeat(60));

  // 按分钟统计
  const [minutely] = await connection.execute(`
    SELECT
      DATE_FORMAT(received_at, '%H:%i') as time,
      COUNT(*) as count
    FROM t_message
    WHERE DATE(received_at) = '2026-03-31'
      AND TIME(received_at) BETWEEN '19:00:00' AND '21:30:00'
    GROUP BY DATE_FORMAT(received_at, '%H:%i')
    ORDER BY time
  `);

  console.log('\n⏱ 19:00-21:30 按分钟分布:');
  console.log('-'.repeat(60));
  minutely.forEach(row => {
    const bar = '█'.repeat(Math.min(row.count, 50));
    console.log(`${row.time}: ${String(row.count).padStart(3)} ${bar}`);
  });

  await connection.end();
}

detailedAnalysis().catch(console.error);
