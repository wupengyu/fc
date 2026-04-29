package cn.daenx.myadmin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_message")
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String msg;

    @TableField("time_stamp")
    private String timeStamp;

    @TableField("msg_id")
    private String msgId;

    @TableField("msg_type")
    private Integer msgType;

    @TableField("msg_source")
    private Integer msgSource;

    private String source;

    @TableField("from_wxid")
    private String fromWxid;

    @TableField("sender_wxid")
    private String senderWxid;

    private String signature;

    private String fingerprint;

    @TableField("raw_json")
    private String rawJson;

    @TableField("received_at")
    private LocalDateTime receivedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
