package cn.daenx.myadmin.modules.domain.event;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Data;

@Data
public class SseMessageVo {
    private String time;
    private Long timestamp;
    private String chat;
    private String username;
    @JSONField(name = "is_group")
    private Boolean isGroup;
    private String sender;
    private String type;
    @JSONField(name = "type_icon")
    private String typeIcon;
    private String content;
    private Integer unread;
    @JSONField(name = "decrypt_ms")
    private Double decryptMs;
    private Integer pages;
    @JSONField(name = "local_id")
    private String localId;
    @JSONField(name = "server_id")
    private String serverId;
    @JSONField(name = "sort_seq")
    private String sortSeq;
    @JSONField(name = "image_url")
    private String imageUrl;
    @JSONField(name = "rich_content")
    private Object richContent;
}
