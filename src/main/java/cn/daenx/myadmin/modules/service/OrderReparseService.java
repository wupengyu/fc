package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.common.constant.OrderConstant;
import cn.daenx.myadmin.entity.OrderParseBatch;
import cn.daenx.myadmin.entity.OrderRaw;
import cn.daenx.myadmin.mapper.OrderParseBatchMapper;
import cn.daenx.myadmin.mapper.OrderRawMapper;
import cn.daenx.myadmin.modules.domain.dto.AiParseResult;
import cn.daenx.myadmin.modules.domain.dto.RawCorrectionRequest;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class OrderReparseService {

    @Autowired
    private OrderRawMapper orderRawMapper;

    @Autowired
    private OrderParseBatchMapper orderParseBatchMapper;

    @Autowired
    private NormalizePersistService normalizePersistService;

    @Autowired
    private AiParseService aiParseService;

    @Autowired
    private PromptCaseService promptCaseService;

    @Autowired
    private RawCorrectionRecordService rawCorrectionRecordService;

    @Autowired
    private OrderWindowService orderWindowService;

    private final AtomicBoolean retryRunning = new AtomicBoolean(false);

    @Value("${order.reparse-enabled:true}")
    private boolean reparseEnabled;

    @Value("${order.reparse-batch-size:20}")
    private int reparseBatchSize;

    @Value("${order.reparse-max-attempts:3}")
    private int reparseMaxAttempts;

    @Value("${order.reparse-cooldown-seconds:90}")
    private long reparseCooldownSeconds;

    @Value("${order.reparse-require-window:true}")
    private boolean reparseRequireWindow;

    public boolean canRunReparseNow() {
        if (!reparseEnabled) {
            return false;
        }
        return !reparseRequireWindow || orderWindowService.isNowInOrderWindow();
    }

    public String getRuntimeWindowText() {
        return orderWindowService.getWindowText();
    }

    public RetrySummary retryFailedOrdersToday() {
        if (!reparseEnabled) {
            return RetrySummary.skipped("AUTO", "自动重跑未启用");
        }
        if (!canRunReparseNow()) {
            return RetrySummary.skipped("AUTO", "当前不在自动重跑时段 " + getRuntimeWindowText());
        }

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime rangeStart = today.atTime(orderWindowService.getWindowStart());
        List<OrderRaw> rawList = loadRawList(rangeStart, now);
        if (rawList.isEmpty()) {
            return RetrySummary.skipped("AUTO", "当前时段没有可检查的原始报单");
        }

        Map<Long, ParseBatchState> stateMap = loadParseBatchStates(rawList);
        List<OrderRaw> retryList = selectRetryCandidates(rawList, stateMap, now);
        if (retryList.isEmpty()) {
            return new RetrySummary(false, "AUTO", "当前没有需要补偿重跑的报单",
                    rawList.size(), 0, 0, 0, 0);
        }

        return executeRetry("AUTO", retryList, rawList.size(), null);
    }

    public RetrySummary reparseDate(String date) {
        if (!reparseEnabled) {
            return RetrySummary.skipped("MANUAL", "重跑未启用");
        }
        if (!canRunReparseNow()) {
            return RetrySummary.skipped("MANUAL", "当前不在重跑时段 " + getRuntimeWindowText());
        }

        LocalDate targetDate = LocalDate.parse(date);
        LocalDateTime startTime = targetDate.atStartOfDay();
        LocalDateTime endTime = targetDate.plusDays(1).atStartOfDay();
        List<OrderRaw> rawList = loadRawList(startTime, endTime);
        if (rawList.isEmpty()) {
            return RetrySummary.skipped("MANUAL", "没有找到该日期的原始报单");
        }

        return executeRetry("MANUAL", rawList, rawList.size(), date);
    }

    public RetrySummary reparseDateForce(String date) {
        if (!reparseEnabled) {
            return RetrySummary.skipped("FORCE", "重跑未启用");
        }

        LocalDate targetDate = LocalDate.parse(date);
        LocalDateTime startTime = targetDate.atStartOfDay();
        LocalDateTime endTime = targetDate.plusDays(1).atStartOfDay();
        List<OrderRaw> rawList = loadRawList(startTime, endTime);
        if (rawList.isEmpty()) {
            return RetrySummary.skipped("FORCE", "没有找到该日期的数据");
        }

        return executeRetry("FORCE", rawList, rawList.size(), date);
    }

    public RetrySummary reparseDateForceFast(String date) {
        if (!reparseEnabled) {
            return RetrySummary.skipped("FAST", "重跑未启用");
        }

        LocalDate targetDate = LocalDate.parse(date);
        LocalDateTime startTime = targetDate.atStartOfDay();
        LocalDateTime endTime = targetDate.plusDays(1).atStartOfDay();
        List<OrderRaw> rawList = loadRawList(startTime, endTime);
        if (rawList.isEmpty()) {
            return RetrySummary.skipped("FAST", "没有找到该日期的数据");
        }

        return executeRetry("FAST", rawList, rawList.size(), date, true);
    }

    public RetrySummary reparseSingleRaw(Long rawId) {
        return reparseSingleRaw(rawId, null);
    }

    public RetrySummary reparseSingleRaw(Long rawId, RawCorrectionRequest correction) {
        if (rawId == null) {
            return RetrySummary.skipped("SINGLE", "rawId不能为空");
        }
        OrderRaw raw = orderRawMapper.selectById(rawId);
        if (raw == null) {
            return RetrySummary.skipped("SINGLE", "未找到对应原始报单");
        }

        int maxAttempts = Math.max(1, reparseMaxAttempts);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return doReparseSingleRaw(raw, correction);
            } catch (RuntimeException e) {
                if (!isDeadlock(e) || attempt >= maxAttempts) {
                    throw e;
                }
                long waitMs = Math.min(300L * attempt, 1500L);
                log.warn("single reparse deadlock, rawId={}, attempt={}/{}, waitMs={}",
                        rawId, attempt, maxAttempts, waitMs, e);
                sleepQuietly(waitMs);
            }
        }

        throw new IllegalStateException("single reparse failed unexpectedly");
    }

    private RetrySummary doReparseSingleRaw(OrderRaw raw, RawCorrectionRequest correction) {
        normalizePersistService.cleanSingleRaw(raw);
        OrderRaw parseRaw = buildReparseRaw(raw, correction);
        log.info("single reparse started, rawId={}, correction={}", raw.getId(), correction);

        AtomicLong successCount = new AtomicLong(0);
        AtomicLong skipCount = new AtomicLong(0);
        AtomicLong failCount = new AtomicLong(0);

        aiParseService.batchParseWithCallback(List.of(parseRaw), (currentRaw, items) -> {
            List<AiParseResult> adjustedItems = applyCorrection(items, correction);
            normalizePersistService.persistSingleWithRetry(currentRaw, adjustedItems);
            rawCorrectionRecordService.record(raw, currentRaw, correction, adjustedItems);
            promptCaseService.recordCorrectionCase(raw, correction, adjustedItems);
            boolean hasSuccess = adjustedItems.stream().anyMatch(AiParseResult::isSuccess);
            boolean hasFailed = adjustedItems.stream().anyMatch(AiParseResult::isFailed);
            boolean hasSkip = adjustedItems.stream().anyMatch(AiParseResult::isSkip);
            if (hasSuccess) {
                successCount.incrementAndGet();
            } else if (hasFailed) {
                failCount.incrementAndGet();
            } else if (hasSkip) {
                skipCount.incrementAndGet();
            } else {
                failCount.incrementAndGet();
            }
        });

        return new RetrySummary(true, "SINGLE", "重跑完成", 1, 1,
                successCount.get(), skipCount.get(), failCount.get());
    }

    private boolean isDeadlock(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("Deadlock found when trying to get lock")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private OrderRaw buildReparseRaw(OrderRaw raw, RawCorrectionRequest correction) {
        String correctedText = normalizeCorrectionText(correction);
        if (correctedText == null) {
            return raw;
        }

        String parseText = buildCorrectionAwareParseText(raw.getRawText(), correctedText);

        OrderRaw parseRaw = new OrderRaw();
        parseRaw.setId(raw.getId());
        parseRaw.setMsgId(raw.getMsgId());
        parseRaw.setFingerprint(raw.getFingerprint());
        parseRaw.setSource(raw.getSource());
        parseRaw.setFromWxid(raw.getFromWxid());
        parseRaw.setSenderWxid(raw.getSenderWxid());
        parseRaw.setRawText(parseText);
        parseRaw.setReceivedAt(raw.getReceivedAt());
        parseRaw.setCreatedAt(raw.getCreatedAt());
        return parseRaw;
    }

    private String buildCorrectionAwareParseText(String originalText, String correctedText) {
        String original = normalize(originalText);
        if (original == null) {
            return correctedText;
        }
        return """
                [原始报单]
                %s

                [人工校正说明]
                %s

                [解析要求]
                以上内容属于同一条微信报单。请以原始报单为主体，结合人工校正说明消除歧义。
                如果人工校正说明明确指出某个数字不是下注号码、不是注数或不是玩法字段，则该数字不得再识别为投注数据。
                如果人工校正说明补充了更完整的报单信息，应优先按人工校正说明修正解析结果。
                """.formatted(original, correctedText);
    }

    private List<AiParseResult> applyCorrection(List<AiParseResult> items, RawCorrectionRequest correction) {
        if (correction == null || correction.getCorrectBet() == null) {
            return items;
        }
        if (correction.getCorrectBet() <= 0) {
            throw new IllegalArgumentException("correctBet必须大于0");
        }

        List<AiParseResult> adjusted = new ArrayList<>();
        String resolvedPlayType = normalizePlayType(correction.getPlayType());
        String rawAiResponse = null;

        for (AiParseResult item : items) {
            if (rawAiResponse == null) {
                rawAiResponse = item.getRawAiResponse();
            }
            if (matchesCorrection(item, correction)) {
                if (resolvedPlayType == null && item.getData() != null) {
                    resolvedPlayType = item.getData().getPlay();
                }
                continue;
            }
            adjusted.add(item);
        }

        adjusted.add(buildCorrectedResult(correction, resolvedPlayType, rawAiResponse));
        return adjusted;
    }

    private boolean matchesCorrection(AiParseResult item, RawCorrectionRequest correction) {
        if (item == null || !item.isSuccess() || item.getData() == null) {
            return false;
        }

        AiParseResult.ParsedData data = item.getData();
        if (!Objects.equals(normalize(data.getCategory()), normalize(correction.getLotteryCategory()))) {
            return false;
        }
        if (!Objects.equals(normalize(data.getGame()), normalize(correction.getGameType()))) {
            return false;
        }

        String expectedPlayType = normalizePlayType(correction.getPlayType());
        if (expectedPlayType != null && !Objects.equals(normalize(data.getPlay()), expectedPlayType)) {
            return false;
        }

        String expectedZone = normalize(correction.getNumberZone());
        String actualZone = normalize(data.getZone());
        String resolvedExpectedZone = expectedZone != null ? expectedZone : OrderConstant.ZONE_MAIN;
        String resolvedActualZone = actualZone != null ? actualZone : OrderConstant.ZONE_MAIN;
        if (!Objects.equals(resolvedExpectedZone, resolvedActualZone)) {
            return false;
        }

        return data.getNumbers() != null && data.getNumbers().contains(correction.getNumber());
    }

    private AiParseResult buildCorrectedResult(RawCorrectionRequest correction,
                                               String resolvedPlayType,
                                               String rawAiResponse) {
        AiParseResult item = new AiParseResult();
        item.setValid(true);
        item.setStatus("SUCCESS");
        item.setRawAiResponse(rawAiResponse);

        AiParseResult.ParsedData data = new AiParseResult.ParsedData();
        data.setCategory(correction.getLotteryCategory());
        data.setGame(correction.getGameType());
        data.setPlay(resolvedPlayType != null ? resolvedPlayType : "直选");
        data.setZone(normalize(correction.getNumberZone()) != null
                ? normalize(correction.getNumberZone())
                : OrderConstant.ZONE_MAIN);
        data.setNumbers(List.of(correction.getNumber()));
        data.setBet(correction.getCorrectBet());
        data.setMultiple(1);
        data.setAmount(BigDecimal.valueOf(correction.getCorrectBet().longValue()).multiply(BigDecimal.valueOf(2L)));
        item.setData(data);
        return item;
    }

    private RetrySummary executeRetry(String mode, List<OrderRaw> retryList, int scannedCount, String issueKey) {
        return executeRetry(mode, retryList, scannedCount, issueKey, false);
    }

    private RetrySummary executeRetry(String mode,
                                      List<OrderRaw> retryList,
                                      int scannedCount,
                                      String issueKey,
                                      boolean realtimeLane) {
        if (!retryRunning.compareAndSet(false, true)) {
            return RetrySummary.skipped(mode, "已有补偿重跑任务正在执行");
        }
        try {
            List<Long> rawIds = retryList.stream().map(OrderRaw::getId).toList();
            normalizePersistService.cleanByRawIds(rawIds, issueKey);
            log.info("{} reparse started, count={}, lane={}", mode, retryList.size(), realtimeLane ? "REALTIME" : "REPARSE");

            AtomicLong successCount = new AtomicLong(0);
            AtomicLong skipCount = new AtomicLong(0);
            AtomicLong failCount = new AtomicLong(0);

            AiParseService.ParseCallback callback = (raw, items) -> {
                normalizePersistService.persistSingleWithRetry(raw, items);
                boolean hasSuccess = items.stream().anyMatch(AiParseResult::isSuccess);
                boolean hasFailed = items.stream().anyMatch(AiParseResult::isFailed);
                boolean hasSkip = items.stream().anyMatch(AiParseResult::isSkip);
                if (hasSuccess) {
                    successCount.incrementAndGet();
                } else if (hasFailed) {
                    failCount.incrementAndGet();
                } else if (hasSkip) {
                    skipCount.incrementAndGet();
                } else {
                    failCount.incrementAndGet();
                }
            };
            if (realtimeLane) {
                aiParseService.batchParseWithCallback(retryList, callback);
            } else {
                aiParseService.batchParseForReparseWithCallback(retryList, callback);
            }

            return new RetrySummary(true, mode, "重跑完成",
                    scannedCount, retryList.size(), successCount.get(), skipCount.get(), failCount.get());
        } finally {
            retryRunning.set(false);
        }
    }

    private List<OrderRaw> selectRetryCandidates(List<OrderRaw> rawList,
                                                 Map<Long, ParseBatchState> stateMap,
                                                 LocalDateTime now) {
        List<OrderRaw> retryList = new ArrayList<>();
        for (OrderRaw raw : rawList) {
            ParseBatchState state = stateMap.get(raw.getId());
            if (state != null && state.attempts() >= reparseMaxAttempts) {
                continue;
            }
            if (!isRetryable(state)) {
                continue;
            }
            if (!isCooldownSatisfied(raw, state, now)) {
                continue;
            }
            retryList.add(raw);
            if (retryList.size() >= reparseBatchSize) {
                break;
            }
        }
        return retryList;
    }

    private boolean isRetryable(ParseBatchState state) {
        if (state == null || state.latest() == null) {
            // 没有任何 batch 记录，说明可能正在解析中，交由 isCooldownSatisfied 判断
            return true;
        }
        OrderParseBatch latest = state.latest();
        Integer parseStatus = latest.getParseStatus();
        if (parseStatus != null && parseStatus == OrderConstant.PARSE_STATUS_SUCCESS) {
            return false;
        }
        if (parseStatus != null && parseStatus == OrderConstant.PARSE_STATUS_FAILED) {
            return looksLikeRetryableFailure(latest.getParseMsg());
        }
        return looksLikeRetryableFailure(latest.getParseMsg());
    }

    private boolean looksLikeRetryableFailure(String parseMsg) {
        if (parseMsg == null || parseMsg.isBlank()) {
            return false;
        }
        String normalized = parseMsg.toLowerCase(Locale.ROOT);
        return normalized.contains("ai api")
                || normalized.contains("temporarily disabled")
                || normalized.contains("timeout")
                || normalized.contains("timed out")
                || normalized.contains("无法解析");
    }

    private boolean isCooldownSatisfied(OrderRaw raw, ParseBatchState state, LocalDateTime now) {
        LocalDateTime baseTime;
        long cooldown = reparseCooldownSeconds;
        if (state != null && state.latest() != null && state.latest().getCreatedAt() != null) {
            baseTime = state.latest().getCreatedAt();
        } else {
            // 没有 batch 记录时仍留出冷却，避免和刚提交的异步解析重叠。
            cooldown = Math.max(reparseCooldownSeconds, 30);
            baseTime = raw.getCreatedAt() != null ? raw.getCreatedAt() : raw.getReceivedAt();
        }
        return baseTime == null || !baseTime.plusSeconds(cooldown).isAfter(now);
    }

    private List<OrderRaw> loadRawList(LocalDateTime startTime, LocalDateTime endTime) {
        LambdaQueryWrapper<OrderRaw> wrapper = new LambdaQueryWrapper<>();
        wrapper.between(OrderRaw::getReceivedAt, startTime, endTime);
        wrapper.orderByAsc(OrderRaw::getReceivedAt, OrderRaw::getId);
        return orderRawMapper.selectList(wrapper);
    }

    private Map<Long, ParseBatchState> loadParseBatchStates(List<OrderRaw> rawList) {
        List<Long> rawIds = rawList.stream().map(OrderRaw::getId).toList();
        if (rawIds.isEmpty()) {
            return Map.of();
        }

        LambdaQueryWrapper<OrderParseBatch> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(OrderParseBatch::getRawId, rawIds);
        wrapper.orderByAsc(OrderParseBatch::getId);
        List<OrderParseBatch> batches = orderParseBatchMapper.selectList(wrapper);

        Map<Long, ParseBatchState> stateMap = new HashMap<>();
        for (OrderParseBatch batch : batches) {
            ParseBatchState current = stateMap.get(batch.getRawId());
            if (current == null) {
                stateMap.put(batch.getRawId(), new ParseBatchState(batch, 1));
            } else {
                stateMap.put(batch.getRawId(), new ParseBatchState(batch, current.attempts() + 1));
            }
        }
        return stateMap;
    }

    private String normalizePlayType(String playType) {
        String normalized = normalize(playType);
        if (normalized == null || "ALL".equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeCorrectionText(RawCorrectionRequest correction) {
        if (correction == null) {
            return null;
        }
        return normalize(correction.getCorrectedText());
    }

    private record ParseBatchState(OrderParseBatch latest, int attempts) {
    }

    public record RetrySummary(boolean executed,
                               String mode,
                               String reason,
                               int scannedCount,
                               int retryCount,
                               long successCount,
                               long skipCount,
                               long failCount) {

        public static RetrySummary skipped(String mode, String reason) {
            return new RetrySummary(false, mode, reason, 0, 0, 0, 0, 0);
        }

        public String toMessage() {
            if (!executed) {
                return mode + "重跑未执行: " + reason;
            }
            return mode + "重跑完成: 扫描=" + scannedCount +
                    ", 重跑=" + retryCount +
                    ", 成功=" + successCount +
                    ", 跳过=" + skipCount +
                    ", 失败=" + failCount;
        }
    }
}
