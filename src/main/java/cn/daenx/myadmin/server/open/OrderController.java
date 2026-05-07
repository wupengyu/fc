package cn.daenx.myadmin.server.open;

import cn.daenx.myadmin.common.vo.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual raw-order injection is disabled. Redis is the only accepted message source.
 */
@RestController
@RequestMapping("/api")
public class OrderController {

    @PostMapping("/orders/raw")
    public Result submitOrder() {
        return Result.error(410, "手工原始报单入口已停用，微信消息只允许写入 Redis 队列 wechat_messages");
    }
}
