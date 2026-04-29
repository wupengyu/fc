package cn.daenx.myadmin.modules.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class TopSourceMessageVO {
    private Integer rank;
    private Long rawId;
    private LocalDateTime receivedAt;
    private String rawText;
    private Integer parseRecordCount;
    private Integer totalOrderCount;
    private BigDecimal totalAmount;
    private List<TopSourceParseRecordVO> parseRecords;
}
