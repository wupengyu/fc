package cn.daenx.myadmin.modules.domain.dto;

import lombok.Data;

@Data
public class RawCorrectionRequest {
    private Long rawId;
    private String lotteryCategory;
    private String gameType;
    private String playType;
    private String numberZone;
    private String number;
    private Integer correctBet;
    private String correctedText;
}
