-- ============================================
-- 2026-03-31 消息入库和AI识别分析
-- ============================================

-- 1. 检查 t_message 表今天的消息数量（SSE原始消息）
SELECT
    '1. SSE原始消息入库情况' AS 分析项,
    COUNT(*) AS 消息总数,
    COUNT(DISTINCT msg_id) AS 去重消息数,
    MIN(received_at) AS 最早消息时间,
    MAX(received_at) AS 最晚消息时间,
    source AS 消息来源
FROM t_message
WHERE DATE(received_at) = '2026-03-31'
GROUP BY source;

-- 2. 检查 t_order_raw 表今天的订单原始记录
SELECT
    '2. 订单原始记录入库情况' AS 分析项,
    COUNT(*) AS 订单原始记录数,
    COUNT(DISTINCT fingerprint) AS 去重记录数,
    MIN(received_at) AS 最早订单时间,
    MAX(received_at) AS 最晚订单时间
FROM t_order_raw
WHERE DATE(received_at) = '2026-03-31';

-- 3. 检查 t_order_parse_batch 表的解析状态分布
SELECT
    '3. AI解析批次状态分布' AS 分析项,
    parse_status AS 解析状态,
    CASE parse_status
        WHEN 1 THEN '成功'
        WHEN 2 THEN '失败'
        WHEN 3 THEN '跳过'
        ELSE '未知'
    END AS 状态说明,
    COUNT(*) AS 数量,
    ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM t_order_parse_batch
                                WHERE raw_id IN (SELECT id FROM t_order_raw WHERE DATE(received_at) = '2026-03-31')), 2) AS 占比百分比
FROM t_order_parse_batch
WHERE raw_id IN (SELECT id FROM t_order_raw WHERE DATE(received_at) = '2026-03-31')
GROUP BY parse_status
ORDER BY parse_status;

-- 4. 检查 t_ai_parse_result 表的解析结果详情
SELECT
    '4. AI解析结果详情' AS 分析项,
    status AS 状态,
    valid AS 是否有效,
    COUNT(*) AS 数量,
    ROUND(COUNT(*) * 100.0 / (SELECT COUNT(*) FROM t_ai_parse_result
                                WHERE issue_key = '2026-03-31'), 2) AS 占比百分比
FROM t_ai_parse_result
WHERE issue_key = '2026-03-31'
GROUP BY status, valid
ORDER BY valid DESC, status;

-- 5. 检查 t_order_item 表的有效订单数量
SELECT
    '5. 有效订单项统计' AS 分析项,
    COUNT(*) AS 有效订单项数,
    COUNT(DISTINCT raw_id) AS 关联原始消息数,
    lottery_category AS 彩票类别,
    game_type AS 游戏类型,
    play_type AS 玩法类型,
    COUNT(*) AS 数量
FROM t_order_item
WHERE issue_key = '2026-03-31'
GROUP BY lottery_category, game_type, play_type
ORDER BY lottery_category, game_type, play_type;

-- 6. 检查 t_ai_call_log 表的AI调用情况
SELECT
    '6. AI调用日志统计' AS 分析项,
    success AS 是否成功,
    CASE success
        WHEN 1 THEN '成功'
        WHEN 0 THEN '失败'
        ELSE '未知'
    END AS 调用状态,
    COUNT(*) AS 调用次数,
    ROUND(AVG(latency_ms), 2) AS 平均延迟毫秒,
    ROUND(AVG(input_tokens), 2) AS 平均输入tokens,
    ROUND(AVG(output_tokens), 2) AS 平均输出tokens,
    ROUND(AVG(total_tokens), 2) AS 平均总tokens
FROM t_ai_call_log
WHERE issue_key = '2026-03-31'
GROUP BY success
ORDER BY success DESC;

-- 7. 消息对齐检查：对比各表的记录数
SELECT
    '7. 消息数量对齐检查' AS 分析项,
    (SELECT COUNT(*) FROM t_message WHERE DATE(received_at) = '2026-03-31') AS t_message表消息数,
    (SELECT COUNT(*) FROM t_order_raw WHERE DATE(received_at) = '2026-03-31') AS t_order_raw表记录数,
    (SELECT COUNT(*) FROM t_order_parse_batch WHERE raw_id IN
        (SELECT id FROM t_order_raw WHERE DATE(received_at) = '2026-03-31')) AS t_order_parse_batch表记录数,
    (SELECT COUNT(DISTINCT raw_id) FROM t_ai_parse_result WHERE issue_key = '2026-03-31') AS t_ai_parse_result表去重raw_id数;

-- 8. AI识别正确率计算
SELECT
    '8. AI识别正确率' AS 分析项,
    SUM(CASE WHEN parse_status = 1 THEN 1 ELSE 0 END) AS 成功数,
    SUM(CASE WHEN parse_status = 2 THEN 1 ELSE 0 END) AS 失败数,
    SUM(CASE WHEN parse_status = 3 THEN 1 ELSE 0 END) AS 跳过数,
    COUNT(*) AS 总数,
    ROUND(SUM(CASE WHEN parse_status = 1 THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) AS 成功率百分比,
    ROUND(SUM(CASE WHEN parse_status = 2 THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) AS 失败率百分比,
    ROUND(SUM(CASE WHEN parse_status = 3 THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) AS 跳过率百分比
FROM t_order_parse_batch
WHERE raw_id IN (SELECT id FROM t_order_raw WHERE DATE(received_at) = '2026-03-31');

-- 9. 失败原因分析（如果有失败记录）
SELECT
    '9. 失败原因TOP10' AS 分析项,
    parse_msg AS 失败原因,
    COUNT(*) AS 失败次数
FROM t_order_parse_batch
WHERE parse_status = 2
  AND raw_id IN (SELECT id FROM t_order_raw WHERE DATE(received_at) = '2026-03-31')
GROUP BY parse_msg
ORDER BY COUNT(*) DESC
LIMIT 10;

-- 10. 检查是否有消息丢失（SSE推送1120条 vs 实际入库）
SELECT
    '10. 消息丢失检查' AS 分析项,
    1120 AS 预期SSE推送数,
    (SELECT COUNT(*) FROM t_message WHERE DATE(received_at) = '2026-03-31') AS 实际入库数,
    1120 - (SELECT COUNT(*) FROM t_message WHERE DATE(received_at) = '2026-03-31') AS 差异数,
    CASE
        WHEN (SELECT COUNT(*) FROM t_message WHERE DATE(received_at) = '2026-03-31') = 1120 THEN '✓ 完全对齐'
        WHEN (SELECT COUNT(*) FROM t_message WHERE DATE(received_at) = '2026-03-31') < 1120 THEN '✗ 有消息丢失'
        ELSE '✗ 数量超出预期'
    END AS 对齐状态;
