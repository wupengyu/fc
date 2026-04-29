package cn.daenx.myadmin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_raw_correction_record")
public class RawCorrectionRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long rawId;
    private String msgId;
    private LocalDateTime receivedAt;
    private String lotteryCategory;
    private String gameType;
    private String playType;
    private String numberZone;
    private String targetNumber;
    private Integer correctBet;
    private String originalText;
    private String correctedText;
    private String parseText;
    private String resultStatus;
    private Integer successItemCount;
    private Integer skipItemCount;
    private Integer failItemCount;
    private String resultSummary;
    private String resultJson;
    private LocalDateTime createdAt;
}
