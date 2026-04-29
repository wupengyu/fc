const mysql = require('mysql2/promise');
const axios = require('axios');

const DB_CONFIG = {
  host: '10.8.0.110',
  port: 3306,
  user: 'root',
  password: 'root',
  database: 'wechat_msg'
};

const SSE_URL = 'http://10.8.0.110:5678/';
const TARGET_DATE = '2026-03-31';
const TARGET_GROUP = '49792312233@chatroom';

async function analyzeDatabase() {
  console.log('='.repeat(60));
  console.log('🔍 消息丢失分析工具');
  console.log('='.repeat(60));
  console.log(`目标日期: ${TARGET_DATE}`);
  console.log(`目标群: ${TARGET_GROUP}\n`);

  let connection;
  try {
    connection = await mysql.createConnection(DB_CONFIG);
    console.log('✓ 数据库连接成功\n');

    // 1. 总体统计
    const [totals] = await connection.execute(`
      SELECT
        COUNT(*) as total,
        COUNT(DISTINCT fingerprint) as unique_count,
        MIN(received_at) as earliest,
        MAX(received_at) as latest,
        source
      FROM t_message
      WHERE DATE(received_at) = ?
      GROUP BY source
    `, [TARGET_DATE]);

    console.log('📊 数据库消息统计:');
    console.log('-'.repeat(60));
    if (totals.length === 0) {
      console.log('⚠ 未找到任何消息记录！');
    } else {
      totals.forEach(row => {
        console.log(`来源: ${row.source}`);
        console.log(`  消息总数: ${row.total}`);
        console.log(`  去重后: ${row.unique_count}`);
        console.log(`  时间范围: ${row.earliest} ~ ${row.latest}\n`);
      });
    }

    // 2. 按小时分布
    const [hourly] = await connection.execute(`
      SELECT
        DATE_FORMAT(received_at, '%H:00') as hour,
        COUNT(*) as count
      FROM t_message
      WHERE DATE(received_at) = ?
      GROUP BY DATE_FORMAT(received_at, '%H:00')
      ORDER BY hour
    `, [TARGET_DATE]);

    console.log('⏰ 按小时分布:');
    console.log('-'.repeat(60));
    hourly.forEach(row => {
      const bar = '█'.repeat(Math.floor(row.count / 5));
      console.log(`${row.hour}: ${String(row.count).padStart(3)} ${bar}`);
    });

    // 3. 目标时间段 (19:00-21:30)
    const [target] = await connection.execute(`
      SELECT COUNT(*) as count
      FROM t_message
      WHERE DATE(received_at) = ?
        AND TIME(received_at) BETWEEN '19:00:00' AND '21:30:00'
    `, [TARGET_DATE]);

    console.log('\n🎯 目标时间段 (19:00-21:30):');
    console.log('-'.repeat(60));
    console.log(`消息数: ${target[0].count}`);

    // 4. 订单处理统计
    const [orders] = await connection.execute(`
      SELECT COUNT(*) as count FROM t_order_raw
      WHERE DATE(received_at) = ?
    `, [TARGET_DATE]);

    console.log(`\n📦 订单处理:`);
    console.log('-'.repeat(60));
    console.log(`订单原始记录: ${orders[0].count}`);

    // 5. 对比分析
    const dbCount = totals.reduce((sum, row) => sum + row.total, 0);
    console.log('\n📈 对比分析:');
    console.log('='.repeat(60));
    console.log(`预期消息数: 1120 条`);
    console.log(`实际入库数: ${dbCount} 条`);
    console.log(`差异: ${1120 - dbCount} 条 (${((1120 - dbCount) / 1120 * 100).toFixed(1)}%)`);

    if (dbCount < 1120) {
      console.log('\n⚠ 可能的原因:');
      console.log('1. 应用在3月31日未运行或中途停止');
      console.log('2. SSE连接中断导致消息丢失');
      console.log('3. 消息被过滤（非群消息、非目标群、非文本）');
      console.log('4. 数据库入库失败');
    }

  } catch (error) {
    console.error('✗ 错误:', error.message);
  } finally {
    if (connection) await connection.end();
    console.log('\n✓ 分析完成');
  }
}

analyzeDatabase();
