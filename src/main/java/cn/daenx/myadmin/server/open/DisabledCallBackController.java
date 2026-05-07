package cn.daenx.myadmin.server.open;

import cn.daenx.myadmin.common.vo.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Explicitly rejects the legacy HTTP callback when Redis is the active message source.
 */
@RestController
@RequestMapping("")
public class DisabledCallBackController {

    @PostMapping("/callback")
    public Result disabled() {
        return Result.error(410, "旧 HTTP 回调入口已停用，微信消息请写入 Redis 队列 wechat_messages");
    }
}
