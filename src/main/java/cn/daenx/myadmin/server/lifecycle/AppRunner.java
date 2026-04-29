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

    @Override
    public void run(ApplicationArguments args) {
        log.info("服务启动成功，本程序的回调地址：{}，请配置到千寻微信框架Pro的回调地址中", "http://127.0.0.1:" + port + contextPath + "/callback");
    }
}
