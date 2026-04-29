#!/usr/bin/env node
'use strict';

const mysql = require('mysql2/promise');

const DB_CONFIG = {
  host: process.env.DB_HOST || '10.8.0.110',
  port: Number(process.env.DB_PORT || 3306),
  user: process.env.DB_USER || 'root',
  password: process.env.DB_PASSWORD || 'root',
  database: process.env.DB_NAME || 'wechat_msg'
};

const TARGET_DATE = process.env.DATE || '2026-03-31';
const OUT_PATH = process.env.OUT || 'tmp_failed_raw_ids_2026-03-31.txt';

function requireDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    throw new Error(`DATE must be YYYY-MM-DD, got: ${value}`);
  }
}

async function main() {
  requireDate(TARGET_DATE);
  const start = `${TARGET_DATE} 00:00:00`;
  const end = `${TARGET_DATE} 23:59:59.999`;

  const conn = await mysql.createConnection(DB_CONFIG);
  try {
    const [rows] = await conn.execute(
      `SELECT r.id AS raw_id,
              r.received_at,
              LEFT(REPLACE(REPLACE(r.raw_text, '\\r', ' '), '\\n', ' '), 120) AS raw_preview,
              b.id AS batch_id,
              b.parse_status,
              b.parse_msg,
              b.created_at AS batch_created_at
         FROM t_order_raw r
         JOIN t_order_parse_batch b ON b.raw_id = r.id
        WHERE r.received_at BETWEEN ? AND ?
          AND b.parse_status = 3
          AND b.created_at = (
                SELECT MAX(b2.created_at)
                  FROM t_order_parse_batch b2
                 WHERE b2.raw_id = r.id
          )
        ORDER BY r.received_at ASC, r.id ASC`,
      [start, end]
    );

    if (!rows.length) {
      console.log(`No latest-failed rows on ${TARGET_DATE}.`);
      return;
    }

    console.log(`Found ${rows.length} latest-failed rows on ${TARGET_DATE}:`);
    for (const row of rows) {
      console.log(
        `rawId=${row.raw_id} | receivedAt=${row.received_at} | batchId=${row.batch_id} | msg=${row.parse_msg || '-'} | preview=${row.raw_preview}`
      );
    }

    const fs = require('fs');
    const ids = rows.map((r) => String(r.raw_id));
    fs.writeFileSync(OUT_PATH, ids.join('\n') + '\n', 'utf8');
    console.log(`Saved raw ids to ${OUT_PATH}`);
  } finally {
    await conn.end();
  }
}

main().catch((err) => {
  console.error(err.message || err);
  process.exit(1);
});
