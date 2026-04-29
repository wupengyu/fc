package cn.daenx.myadmin.modules.domain.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class TopSourceParseRecordVO {
    private Long parseResultId;
    private Integer itemIndex;
    private String category;
    private String gameType;
    private String playType;
    private String numberZone;
    private List<String> numbers;
    private Integer bet;
    private BigDecimal amount;
    private String status;
    private String reason;
}
