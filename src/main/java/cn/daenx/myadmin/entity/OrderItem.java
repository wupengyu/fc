package cn.daenx.myadmin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_order_item")
public class OrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long rawId;
    private Long batchId;
    private Integer itemNo;

    private String lotteryCategory;
    private String gameType;
    private String playType;

    private String issueNo;
    private String issueKey;

    private Integer betCount;
    private Integer groupCount;
    private Integer multiple;

    private BigDecimal totalAmount;
    private String amountAllocMode;

    private String rawText;
    private Integer statApplied;

    private LocalDateTime createdAt;
}
