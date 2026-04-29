package cn.daenx.myadmin.modules.domain.dto;

import lombok.Data;

@Data
public class StatsQueryRequest {
    private String lotteryCategory;
    private String gameType;
    private String playType;
    private String issueKey;
    private Long rawId;
    private String numberZone;
    private String number;
    private String betNumber;
    private String messageKeyword;
    private String sortBy = "count";
    private String sortOrder = "desc";
    private Integer page = 1;
    private Integer pageSize = 20;
    private Integer topN = 20;
}
