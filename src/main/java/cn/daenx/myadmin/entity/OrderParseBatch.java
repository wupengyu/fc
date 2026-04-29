package cn.daenx.myadmin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_order_parse_batch")
public class OrderParseBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long rawId;
    private String parseVersion;
    private String aiModel;
    private Integer aiLatencyMs;
    private String aiResponseBody;
    private Integer parseStatus;
    private String parseMsg;
    private Integer isEffective;
    private LocalDateTime createdAt;
}
