package cn.daenx.myadmin.modules.domain.event;

import lombok.Data;

@Data
public class InjectSuccessReqVo {

    /**
     * 监听端口
     */
    private String port;

    /**
     * 进程PID
     */
    private String pid;
}
