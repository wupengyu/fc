package cn.daenx.myadmin.server.lifecycle;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 项目启动后，初始化
 */
@Component
@Slf4j
public class AppRunner implements ApplicationRunner {
    @Value("${server.port}")
    public String port;
    @Value("${server.servlet.context-path}")
    public String contextPath;
    @Value("${redis.queue-name:wechat_messages}")
    public String queueName;
    @Value("${wechat.callback.enabled:false}")
    public boolean callbackEnabled;

    @Override
    public void run(ApplicationArguments args) {
        log.info("服务启动成功，当前微信消息入口为 Redis 队列：{}", queueName);
        if (callbackEnabled) {
            log.warn("旧 HTTP 回调入口已启用：{}", "http://127.0.0.1:" + port + contextPath + "/callback");
        } else {
            log.info("旧 HTTP 回调入口未启用，/callback 不再接收微信消息");
        }
    }
}
