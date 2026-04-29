package cn.daenx.myadmin.modules.domain.vo;

import lombok.Data;

import java.util.List;

@Data
public class TopSourceMessagePanelVO {
    private String lotteryCategory;
    private String gameType;
    private String playType;
    private String issueKey;
    private Long rawId;
    private String numberZone;
    private String betNumber;
    private String messageKeyword;
    private String sortBy;
    private Integer page;
    private Integer pageSize;
    private Integer totalItems;
    private Integer totalPages;
    private List<TopSourceMessageVO> items;
    private String queryTime;
}
