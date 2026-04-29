package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.entity.OrderRaw;
import cn.daenx.myadmin.entity.RawCorrectionRecord;
import cn.daenx.myadmin.mapper.RawCorrectionRecordMapper;
import cn.daenx.myadmin.modules.domain.dto.AiParseResult;
import cn.daenx.myadmin.modules.domain.dto.RawCorrectionRequest;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class RawCorrectionRecordService {

    @Autowired
    private RawCorrectionRecordMapper rawCorrectionRecordMapper;

    public void record(OrderRaw originalRaw,
                       OrderRaw parseRaw,
                       RawCorrectionRequest correction,
                       List<AiParseResult> finalItems) {
        if (!hasCorrection(correction) || originalRaw == null) {
            return;
        }

        try {
            List<AiParseResult> items = finalItems == null ? List.of() : finalItems;
            int successCount = (int) items.stream().filter(AiParseResult::isSuccess).count();
            int failCount = (int) items.stream().filter(AiParseResult::isFailed).count();
            int skipCount = (int) items.stream().filter(AiParseResult::isSkip).count();

            RawCorrectionRecord record = new RawCorrectionRecord();
            record.setRawId(originalRaw.getId());
            record.setMsgId(originalRaw.getMsgId());
            record.setReceivedAt(originalRaw.getReceivedAt());
            record.setLotteryCategory(normalize(correction.getLotteryCategory()));
            record.setGameType(normalize(correction.getGameType()));
            record.setPlayType(normalize(correction.getPlayType()));
            record.setNumberZone(normalize(correction.getNumberZone()));
            record.setTargetNumber(normalize(correction.getNumber()));
            record.setCorrectBet(correction.getCorrectBet());
            record.setOriginalText(originalRaw.getRawText());
            record.setCorrectedText(normalize(correction.getCorrectedText()));
            record.setParseText(resolveParseText(originalRaw, parseRaw));
            record.setResultStatus(resolveResultStatus(successCount, failCount, skipCount));
            record.setSuccessItemCount(successCount);
            record.setFailItemCount(failCount);
            record.setSkipItemCount(skipCount);
            record.setResultSummary(buildResultSummary(items, successCount, failCount, skipCount));
            record.setResultJson(buildResultJson(items));
            record.setCreatedAt(LocalDateTime.now());
            rawCorrectionRecordMapper.insert(record);
            log.info("raw correction record saved: rawId={}, id={}", record.getRawId(), record.getId());
        } catch (Exception e) {
            log.warn("save raw correction record failed: rawId={}, msg={}",
                    originalRaw.getId(), e.getMessage(), e);
        }
    }

    private boolean hasCorrection(RawCorrectionRequest correction) {
        if (correction == null) {
            return false;
        }
        return (correction.getCorrectBet() != null && correction.getCorrectBet() > 0)
                || normalize(correction.getCorrectedText()) != null;
    }

    private String resolveParseText(OrderRaw originalRaw, OrderRaw parseRaw) {
        if (parseRaw != null && parseRaw.getRawText() != null && !parseRaw.getRawText().isBlank()) {
            return parseRaw.getRawText();
        }
        return originalRaw.getRawText();
    }

    private String resolveResultStatus(int successCount, int failCount, int skipCount) {
        if (successCount > 0 && (failCount > 0 || skipCount > 0)) {
            return "PARTIAL_SUCCESS";
        }
        if (successCount > 0) {
            return "SUCCESS";
        }
        if (failCount > 0) {
            return "FAIL";
        }
        if (skipCount > 0) {
            return "SKIP";
        }
        return "EMPTY";
    }

    private String buildResultSummary(List<AiParseResult> items, int successCount, int failCount, int skipCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("success=").append(successCount)
                .append(", fail=").append(failCount)
                .append(", skip=").append(skipCount);

        List<String> detailList = items.stream()
                .map(this::formatItem)
                .filter(text -> text != null && !text.isBlank())
                .limit(10)
                .toList();
        if (!detailList.isEmpty()) {
            sb.append(" | ").append(String.join(" | ", detailList));
        }
        return sb.toString();
    }

    private String formatItem(AiParseResult item) {
        if (item == null) {
            return null;
        }
        if (item.isSuccess() && item.getData() != null) {
            AiParseResult.ParsedData data = item.getData();
            return String.format("%s %s/%s/%s/%s numbers=%s bet=%s amount=%s",
                    item.getStatus(),
                    nullSafe(data.getCategory()),
                    nullSafe(data.getGame()),
                    nullSafe(data.getPlay()),
                    nullSafe(data.getZone()),
                    data.getNumbers(),
                    data.getBet(),
                    data.getAmount());
        }
        return String.format("%s reason=%s",
                nullSafe(item.getStatus()),
                nullSafe(item.getErrorOrReason()));
    }

    private String buildResultJson(List<AiParseResult> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> payload = new ArrayList<>();
        for (AiParseResult item : items) {
            Map<String, Object> itemMap = new LinkedHashMap<>();
            itemMap.put("status", item.getStatus());
            itemMap.put("valid", item.isValid());
            itemMap.put("reason", item.getErrorOrReason());
            if (item.getData() != null) {
                AiParseResult.ParsedData data = item.getData();
                Map<String, Object> dataMap = new LinkedHashMap<>();
                dataMap.put("category", data.getCategory());
                dataMap.put("game", data.getGame());
                dataMap.put("play", data.getPlay());
                dataMap.put("zone", data.getZone());
                dataMap.put("numbers", data.getNumbers());
                dataMap.put("bet", data.getBet());
                dataMap.put("multiple", data.getMultiple());
                dataMap.put("amount", data.getAmount());
                itemMap.put("data", dataMap);
            }
            payload.add(itemMap);
        }
        return JSON.toJSONString(payload);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String nullSafe(String value) {
        return value == null ? "-" : value;
    }
}
