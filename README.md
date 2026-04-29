## 千寻微信框架Pro HTTP Java开发demo
官网：https://qxpro.apifox.cn/
### 介绍

千寻微信框架Pro的HTTP Java开发demo，基于`java21`

各个事件的处理类在下`cn.daenx.myadmin.modules.service.impl`

我已经把`注入成功（10000）`、`收到私聊消息（10009）`、`收到群聊消息（10008）`demo给放上去了，自行学习，如果你需要其他事件，自己添加！

里面包含了发送群消息 带艾特群成员，emoji表情、换行 等

`cn.daenx.myadmin.modules.utils.QianXunApi`是封装的框架API接口，我只写了`发送文本消息（sendText）`、`退还收款（returnTrans）`，其他的自己写！

`cn.daenx.myadmin.modules.utils.QianXunText`是封装的文本代码


### 使用教程

配置文件是`src/main/resources/application.yml`

其他的自己看吧，我写的已经很简单了，别告诉我 你玩java的这个项目你看不懂