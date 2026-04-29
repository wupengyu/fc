package cn.daenx.myadmin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_ai_parse_result")
public class AiParseResultRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long rawId;
    private Long batchId;
    private Integer itemIndex;
    private Integer valid;
    private String status;
    private String reason;

    private String category;
    private String game;
    private String play;
    private String zone;
    private String numbers;       // JSON数组，如 ["375","572"]
    private Integer bet;
    private Integer multiple;
    private BigDecimal amount;

    private String issueKey;
    private LocalDateTime createdAt;
}
