package cn.daenx.myadmin.modules.domain;

import com.alibaba.fastjson2.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BaseReqVo {
    private String type;
    private JSONObject data;
}
