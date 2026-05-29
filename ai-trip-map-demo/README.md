# AI旅游攻略生成器

这是一个独立静态 Demo：输入城市、天数和偏好后，调用火山方舟/豆包兼容接口生成每日行程，再用高德地图绘制当天连续路线。没有配置 AI 接口时，可以直接使用内置长沙示例行程测试地图和交互。

## 使用

1. 打开 `travel.html`。
2. 填写高德 `Web JS Key` 和 `安全密钥`，点击“保存”或“加载地图”。
3. 可选：填写火山方舟 `API Key` 和 `Endpoint ID / Model`，或使用下面的本地代理。
4. 点击“生成AI详细行程+地图路线”。

## 高德服务

高德控制台中的 Key 需要启用 JavaScript API，并确保路线规划相关服务可用。页面使用了：

- `AMap.Geocoder`
- `AMap.Driving`
- `AMap.Walking`
- `AMap.Riding`

## 火山方舟接口

页面请求地址为：

```text
https://ark.cn-beijing.volces.com/api/v3/chat/completions
```

`Endpoint ID / Model` 填你在方舟控制台创建的 Endpoint ID。出于演示方便，当前实现是纯前端请求；正式部署时建议把 AI API Key 放到后端代理中，避免密钥暴露在浏览器里。

## 推荐：本地 AI 代理

PowerShell 启动：

```powershell
$env:ARK_API_KEY="你的火山方舟API Key"
$env:ARK_MODEL="你的Endpoint ID"
.\ark-proxy.ps1
```

然后在页面的“AI代理地址”填写：

```text
http://127.0.0.1:8787/api/chat
```

使用代理时，页面里可以不填写火山方舟 API Key。
