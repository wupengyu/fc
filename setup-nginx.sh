#!/bin/bash
# Nginx 配置更新脚本
# 在服务器 8.138.83.68 上执行

# 备份现有配置
cp /etc/nginx/conf.d/cai.conf /etc/nginx/conf.d/cai.conf.bak 2>/dev/null

# 创建新配置
cat > /etc/nginx/conf.d/cai.conf << 'EOF'
server {
    listen 80;
    server_name cai.lantelli.com;

    # 根路径访问时返回 statistics.html 页面
    location = / {
        proxy_pass http://10.8.0.10:8989/wechat/statistics.html;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # /wechat 路径下的所有请求（包括 API）正常转发
    location /wechat/ {
        proxy_pass http://10.8.0.10:8989/wechat/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # 处理 favicon.ico 等静态资源
    location /favicon.ico {
        proxy_pass http://10.8.0.10:8989/wechat/favicon.ico;
    }
}
EOF

echo "配置文件已创建"

# 测试配置
nginx -t

# 如果测试通过，重载配置
if [ $? -eq 0 ]; then
    nginx -s reload
    echo "Nginx 配置已重载"
else
    echo "配置测试失败，请检查配置"
fi
