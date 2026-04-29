package cn.daenx.myadmin.modules.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class StatsVO {
    private String lotteryCategory;
    private String gameType;
    private String playType;
    private String issueKey;
    private String number;
    private String sortOrder;
    private int page;
    private int pageSize;
    private int totalItems;
    private int totalPages;
    private int totalOrders;
    private BigDecimal totalAmount;
    private List<NumberStatsVO> stats;
    private String queryTime;
}
