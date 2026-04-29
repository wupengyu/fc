package cn.daenx.myadmin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("t_number_stats")
public class NumberStats {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String lotteryCategory;
    private String gameType;
    private String playType;
    private String issueKey;

    private String numberZone;
    private String number;

    private Integer orderCount;
    private BigDecimal sumAmount;
    private LocalDateTime lastUpdatedAt;
}
