#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
分析3月31日晚上7点到9点30的消息丢失情况
对比SSE服务推送的消息和数据库实际入库的消息
"""

import pymysql
import requests
import json
from datetime import datetime, time
from collections import defaultdict

# 数据库配置
DB_CONFIG = {
    'host': '10.8.0.110',
    'port': 3306,
    'user': 'root',
    'password': 'root',
    'database': 'wechat_msg',
    'charset': 'utf8mb4'
}

# SSE服务配置
SSE_URL = 'http://10.8.0.110:5678/'
TARGET_GROUP = '49792312233@chatroom'
TARGET_DATE = '2026-03-31'
START_TIME = time(19, 0)  # 19:00
END_TIME = time(21, 30)   # 21:30

def connect_db():
    """连接数据库"""
    try:
        conn = pymysql.connect(**DB_CONFIG)
        print(f"✓ 数据库连接成功: {DB_CONFIG['host']}:{DB_CONFIG['port']}")
        return conn
    except Exception as e:
        print(f"✗ 数据库连接失败: {e}")
        return None

def get_db_messages(conn):
    """获取数据库中的消息"""
    cursor = conn.cursor(pymysql.cursors.DictCursor)

    # 查询t_message表
    sql = """
        SELECT
            id,
            msg_id,
            time_stamp,
            received_at,
            from_wxid,
            sender_wxid,
            msg,
            source,
            fingerprint
        FROM t_message
        WHERE DATE(received_at) = %s
          AND TIME(received_at) BETWEEN %s AND %s
        ORDER BY received_at
    """

    cursor.execute(sql, (TARGET_DATE, START_TIME, END_TIME))
    messages = cursor.fetchall()
    cursor.close()

    return messages

def get_sse_messages():
    """获取SSE服务的消息历史"""
    try:
        # 尝试获取SSE服务的历史消息接口
        response = requests.get(f"{SSE_URL}history", timeout=10)
        if response.status_code == 200:
            return response.json()
        else:
            print(f"⚠ SSE服务返回状态码: {response.status_code}")
            return None
    except Exception as e:
        print(f"⚠ 无法连接SSE服务: {e}")
        print(f"  提示: 请手动检查 {SSE_URL} 是否有历史消息接口")
        return None

def analyze_db_data(conn):
    """分析数据库数据"""
    print("\n" + "="*60)
    print("📊 数据库消息分析")
    print("="*60)

    cursor = conn.cursor(pymysql.cursors.DictCursor)

    # 1. 总体统计
    sql_total = """
        SELECT
            COUNT(*) as total,
            COUNT(DISTINCT fingerprint) as unique_count,
            MIN(received_at) as earliest,
            MAX(received_at) as latest,
            source
        FROM t_message
        WHERE DATE(received_at) = %s
        GROUP BY source
    """
    cursor.execute(sql_total, (TARGET_DATE,))
    totals = cursor.fetchall()

    print(f"\n1. 总体统计 ({TARGET_DATE}):")
    for row in totals:
        print(f"   来源: {row['source']}")
        print(f"   - 消息总数: {row['total']}")
        print(f"   - 去重后: {row['unique_count']}")
        print(f"   - 时间范围: {row['earliest']} ~ {row['latest']}")

    # 2. 时间段统计
    sql_time = """
        SELECT
            DATE_FORMAT(received_at, '%H:00') as hour,
            COUNT(*) as count
        FROM t_message
        WHERE DATE(received_at) = %s
        GROUP BY DATE_FORMAT(received_at, '%H:00')
        ORDER BY hour
    """
    cursor.execute(sql_time, (TARGET_DATE,))
    time_stats = cursor.fetchall()

    print(f"\n2. 按小时分布:")
    for row in time_stats:
        bar = '█' * (row['count'] // 5)
        print(f"   {row['hour']}: {row['count']:3d} {bar}")

    # 3. 目标时间段统计
    sql_target = """
        SELECT
            COUNT(*) as total,
            COUNT(DISTINCT from_wxid) as group_count
        FROM t_message
        WHERE DATE(received_at) = %s
          AND TIME(received_at) BETWEEN %s AND %s
    """
    cursor.execute(sql_target, (TARGET_DATE, START_TIME, END_TIME))
    target = cursor.fetchone()

    print(f"\n3. 目标时间段 (19:00-21:30):")
    print(f"   - 消息数: {target['total']}")
    print(f"   - 群数: {target['group_count']}")

    # 4. 订单处理统计
    sql_order = """
        SELECT
            COUNT(*) as raw_count
        FROM t_order_raw
        WHERE DATE(received_at) = %s
    """
    cursor.execute(sql_order, (TARGET_DATE,))
    order = cursor.fetchone()

    print(f"\n4. 订单处理:")
    print(f"   - 订单原始记录: {order['raw_count']}")

    cursor.close()
    return target['total']

def main():
    print("="*60)
    print("🔍 消息丢失分析工具")
    print("="*60)
    print(f"目标日期: {TARGET_DATE}")
    print(f"目标时间: {START_TIME.strftime('%H:%M')} - {END_TIME.strftime('%H:%M')}")
    print(f"目标群: {TARGET_GROUP}")

    # 连接数据库
    conn = connect_db()
    if not conn:
        return

    try:
        # 分析数据库数据
        db_count = analyze_db_data(conn)

        # 尝试获取SSE数据
        print("\n" + "="*60)
        print("📡 SSE服务消息分析")
        print("="*60)

        sse_messages = get_sse_messages()
        if sse_messages:
            print(f"✓ 获取到SSE历史消息: {len(sse_messages)} 条")
            # TODO: 进一步分析SSE消息
        else:
            print("⚠ 无法获取SSE历史消息")
            print(f"\n请手动检查以下内容:")
            print(f"1. 访问 {SSE_URL} 查看SSE服务状态")
            print(f"2. 检查SSE服务是否有历史消息查询接口")
            print(f"3. 确认SSE服务在3月31日是否正常推送消息")

        # 对比分析
        print("\n" + "="*60)
        print("📈 对比分析")
        print("="*60)
        print(f"预期消息数: 1120 条")
        print(f"实际入库数: {db_count} 条")
        print(f"差异: {1120 - db_count} 条 ({(1120 - db_count) / 1120 * 100:.1f}%)")

        if db_count < 1120:
            print(f"\n⚠ 可能的原因:")
            print(f"1. 应用在3月31日未运行或中途停止")
            print(f"2. SSE连接中断导致消息丢失")
            print(f"3. 消息被过滤（非群消息、非目标群、非文本）")
            print(f"4. 数据库入库失败")

    finally:
        conn.close()
        print("\n✓ 分析完成")

if __name__ == '__main__':
    main()
