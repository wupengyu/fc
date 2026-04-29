package cn.daenx.myadmin.modules.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class NumberStatsVO {
    private int rank;
    private String numberZone;
    private String number;
    private int orderCount;
    private BigDecimal sumAmount;
}
