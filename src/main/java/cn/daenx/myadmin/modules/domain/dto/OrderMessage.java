package cn.daenx.myadmin.modules.domain.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrderMessage {
    private String msgId;
    private String fingerprint;
    private String source;
    private String fromWxid;
    private String senderWxid;
    private String rawText;
    private LocalDateTime receivedAt;
}
