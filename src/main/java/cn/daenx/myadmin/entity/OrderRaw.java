package cn.daenx.myadmin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_order_raw")
public class OrderRaw {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String msgId;
    private String fingerprint;
    private String source;
    private String fromWxid;
    private String senderWxid;
    private String rawText;
    private LocalDateTime receivedAt;
    private LocalDateTime createdAt;
}
