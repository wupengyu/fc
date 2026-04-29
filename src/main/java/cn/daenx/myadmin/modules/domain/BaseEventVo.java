package cn.daenx.myadmin.modules.domain;

import lombok.Data;

@Data
public class BaseEventVo {
    private String type;
    private String des;
    private String timestamp;
    private String wxid;
    private Integer port;
    private Integer pid;
    private String flag;
}
