package cn.daenx.myadmin.server.open;


import cn.daenx.myadmin.common.exception.MyException;
import cn.daenx.myadmin.common.vo.Result;
import cn.daenx.myadmin.modules.domain.BaseEventVo;
import cn.daenx.myadmin.modules.service.EventHandleService;
import cn.hutool.extra.spring.SpringUtil;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

/**
 * Legacy HTTP callback receiver. Disabled by default because messages are now consumed from Redis.
 */
@RestController
@RequestMapping("")
@ConditionalOnProperty(name = "wechat.callback.enabled", havingValue = "true")
@Slf4j
public class CallBackController {
    private EventHandleService getService(String event) {
        try {
            EventHandleService service = SpringUtil.getApplicationContext().getBean("event:" + event, EventHandleService.class);
            return service;
        } catch (NoSuchBeanDefinitionException e) {
            throw new MyException("本服务未开发支持" + event + "事件");
        }
    }

    @PostMapping("/callback")
    public Result page(@RequestBody String jsonStr) {
        log.info("legacy callback event received: {}", jsonStr);
        JSONObject req = JSONObject.parseObject(jsonStr);
        BaseEventVo data = req.getObject("data", BaseEventVo.class);
        JSONObject jsonObject = req.getJSONObject("data").getJSONObject("data");
        getService(req.getString("event")).handle(data, jsonObject);
        return Result.ok();
    }

}
