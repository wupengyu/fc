package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.modules.domain.BaseEventVo;
import com.alibaba.fastjson2.JSONObject;

public interface EventHandleService {
    void handle(BaseEventVo baseEventVo, JSONObject jsonObject);
}
