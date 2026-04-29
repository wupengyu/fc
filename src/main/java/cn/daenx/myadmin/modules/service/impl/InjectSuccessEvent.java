package cn.daenx.myadmin.modules.service.impl;

import cn.daenx.myadmin.common.constant.EventConstant;
import cn.daenx.myadmin.modules.domain.BaseEventVo;
import cn.daenx.myadmin.modules.domain.event.InjectSuccessReqVo;
import cn.daenx.myadmin.modules.service.EventHandleService;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 注入成功（10000）
 */
@Slf4j
@Service("event:" + EventConstant.injectSuccess)
public class InjectSuccessEvent implements EventHandleService {

    @Override
    public void handle(BaseEventVo baseEventVo, JSONObject jsonObject) {
        InjectSuccessReqVo data = jsonObject.toJavaObject(InjectSuccessReqVo.class);
        log.info("注入成功，进程ID：{}，监听端口：{}", data.getPid(), data.getPort());
    }
}
