#!/bin/bash
echo "=========================================="
echo "  wechat-message-parser 启动脚本"
echo "=========================================="
echo ""

export JAVA_HOME="/d/devtools/jdk"
export PATH="$JAVA_HOME/bin:$PATH"

echo "[配置信息]"
echo "JAVA_HOME: $JAVA_HOME"
echo "端口: 8989"
echo "消息入口: Redis 队列 wechat_messages"
echo "统计页面: http://127.0.0.1:8989/wechat/stats.html"
echo ""
echo "[实时日志输出]"
echo "=========================================="

mvn spring-boot:run -s "E:/qian-xun-pro-wechat-http-demo-master/settings-temp.xml" 2>&1
