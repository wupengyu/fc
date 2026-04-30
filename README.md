## WeChat Message Parser

本项目用于从 Redis 队列读取微信群消息，落库后在下单时间窗口内进行报单解析、统计和查询。

当前消息入口：

```text
Redis list: wechat_messages
```

旧的 HTTP `/callback` 回调入口默认已停用，不再作为微信消息来源。若误请求 `/wechat/callback`，服务会返回禁用提示，不会进入消息处理链路。

常用入口：

```text
启动脚本: one-click-start.bat
健康检查: http://127.0.0.1:8989/wechat/api/runtime-status
统计页面: http://127.0.0.1:8989/wechat/stats.html
```

主要配置文件：

```text
src/main/resources/application.yml
```
