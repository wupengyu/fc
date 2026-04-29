package cn.daenx.myadmin.server.open;

import cn.daenx.myadmin.common.constant.OrderConstant;
import cn.daenx.myadmin.common.vo.Result;
import cn.daenx.myadmin.modules.domain.dto.AiParseResult;
import cn.daenx.myadmin.entity.AiParseResultRecord;
import cn.daenx.myadmin.entity.Message;
import cn.daenx.myadmin.entity.OrderParseBatch;
import cn.daenx.myadmin.entity.OrderRaw;
import cn.daenx.myadmin.mapper.AiParseResultMapper;
import cn.daenx.myadmin.mapper.MessageMapper;
import cn.daenx.myadmin.mapper.OrderParseBatchMapper;
import cn.daenx.myadmin.mapper.OrderRawMapper;
import cn.daenx.myadmin.modules.domain.dto.RawCorrectionRequest;
import cn.daenx.myadmin.modules.domain.dto.StatsQueryRequest;
import cn.daenx.myadmin.modules.domain.vo.StatsVO;
import cn.daenx.myadmin.modules.domain.vo.TopSourceMessagePanelVO;
import cn.daenx.myadmin.modules.service.AiParseService;
import cn.daenx.myadmin.modules.service.MessageBufferService;
import cn.daenx.myadmin.modules.service.NormalizePersistService;
import cn.daenx.myadmin.modules.service.OrderBufferService;
import cn.daenx.myadmin.modules.service.OrderReparseService;
import cn.daenx.myadmin.modules.service.OrderWindowService;
import cn.daenx.myadmin.modules.service.StatsService;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api")
public class StatsController {

    private static final Pattern THREE_DIGIT = Pattern.compile("^\\d{3}$");

    @Autowired
    private StatsService statsService;

    @Autowired
    private OrderRawMapper orderRawMapper;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private AiParseResultMapper aiParseResultMapper;

    @Autowired
    private OrderParseBatchMapper orderParseBatchMapper;

    @Autowired
    private OrderReparseService orderReparseService;

    @Autowired
    private NormalizePersistService normalizePersistService;

    @Autowired
    private MessageBufferService messageBufferService;

    @Autowired
    private OrderBufferService orderBufferService;

    @Autowired
    private OrderWindowService orderWindowService;

    @Autowired
    private AiParseService aiParseService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Value("${redis.queue-name:wechat_messages}")
    private String queueName;

    @Value("${ai.compare-model:glm-4.7}")
    private String compareModel;

    @GetMapping("/check-messages")
    public Result checkMessages() {
        LocalDateTime today730PM = LocalDate.now().atTime(orderWindowService.getWindowStart());
        LambdaQueryWrapper<Message> qw = new LambdaQueryWrapper<>();
        qw.ge(Message::getReceivedAt, today730PM);
        long dbCount = messageMapper.selectCount(qw);
        Long redisCount = redisTemplate.opsForList().size(queueName);
        if (redisCount == null) {
            redisCount = 0L;
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("timeRange", today730PM + " 至今");
        data.put("dbCount", dbCount);
        data.put("redisQueueCount", redisCount);
        data.put("total", dbCount + redisCount);
        data.put("status", redisCount == 0 ? "所有消息已入库" : "Redis 队列还有 " + redisCount + " 条待处理");
        return Result.ok(data);
    }

    @GetMapping("/stats")
    public Result stats(@RequestParam String lotteryCategory,
                        @RequestParam String gameType,
                        @RequestParam(required = false) String playType,
                        @RequestParam String issueKey,
                        @RequestParam(required = false) String numberZone,
                        @RequestParam(required = false) String number,
                        @RequestParam(required = false, defaultValue = "count") String sortBy,
                        @RequestParam(required = false, defaultValue = "desc") String sortOrder,
                        @RequestParam(required = false, defaultValue = "1") Integer page,
                        @RequestParam(required = false) Integer pageSize,
                        @RequestParam(required = false, defaultValue = "20") Integer topN) {
        StatsQueryRequest req = buildStatsRequest(
                lotteryCategory,
                gameType,
                playType,
                issueKey,
                numberZone,
                number,
                null,
                null,
                sortBy,
                sortOrder,
                page,
                pageSize,
                topN
        );
        StatsVO data = statsService.queryTopN(req);
        return Result.ok(data);
    }

    @GetMapping("/top-source-messages")
    public Result topSourceMessages(@RequestParam(required = false) String lotteryCategory,
                                    @RequestParam(required = false) String gameType,
                                    @RequestParam(required = false) String playType,
                                    @RequestParam String issueKey,
                                    @RequestParam(required = false) Long rawId,
                                    @RequestParam(required = false) String numberZone,
                                    @RequestParam(required = false) String betNumber,
                                    @RequestParam(required = false) String messageKeyword,
                                    @RequestParam(required = false, defaultValue = "count") String sortBy,
                                    @RequestParam(required = false, defaultValue = "1") Integer page,
                                    @RequestParam(required = false) Integer pageSize,
                                    @RequestParam(required = false) Integer topN) {
        if (rawId != null && rawId <= 0) {
            return Result.error(400, "rawId 必须大于 0");
        }
        if (betNumber != null && !betNumber.isBlank() && !THREE_DIGIT.matcher(betNumber.trim()).matches()) {
            return Result.error(400, "betNumber 必须是 3 位数字");
        }
        StatsQueryRequest req = buildStatsRequest(
                lotteryCategory,
                gameType,
                playType,
                issueKey,
                numberZone,
                null,
                betNumber,
                messageKeyword,
                sortBy,
                "desc",
                page,
                pageSize,
                topN
        );
        req.setRawId(rawId);
        TopSourceMessagePanelVO data = statsService.queryTopSourceMessages(req);
        return Result.ok(data);
    }

    @GetMapping("/message-count")
    public Result messageCount(@RequestParam String date) {
        LocalDate targetDate = LocalDate.parse(date);
        LocalDateTime start = targetDate.atStartOfDay();
        LocalDateTime end = targetDate.plusDays(1).atStartOfDay();
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(Message::getReceivedAt, start);
        wrapper.lt(Message::getReceivedAt, end);
        long count = messageMapper.selectCount(wrapper);
        return Result.ok("date=" + date + ", messageCount=" + count);
    }

    @GetMapping("/raw-count")
    public Result rawCount(@RequestParam String date) {
        LocalDate targetDate = LocalDate.parse(date);
        LocalDateTime startTime = targetDate.atTime(orderWindowService.getWindowStart());
        LocalDateTime endTime = targetDate.atTime(orderWindowService.getWindowEnd());

        LambdaQueryWrapper<OrderRaw> wrapper = new LambdaQueryWrapper<>();
        wrapper.between(OrderRaw::getReceivedAt, startTime, endTime);
        long count = orderRawMapper.selectCount(wrapper);
        return Result.ok("date=" + date + ", rawCount=" + count);
    }

    @GetMapping("/runtime-status")
    public Result runtimeStatus(@RequestParam(required = false) String date) {
        LocalDate targetDate = date != null && !date.isBlank() ? LocalDate.parse(date) : LocalDate.now();
        LocalDateTime start = targetDate.atStartOfDay();
        LocalDateTime end = targetDate.plusDays(1).atStartOfDay();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", targetDate.toString());
        data.put("serverTime", LocalDateTime.now().toString());
        data.put("reparseWindow", orderReparseService.getRuntimeWindowText());
        data.put("reparseAllowedNow", orderReparseService.canRunReparseNow());
        data.put("messageRetryBuffer", messageBufferService.pendingCount());
        data.put("orderBuffer", orderBufferService.pendingCount());
        data.put("rawOrderCount", countRawOrders(start, end));
        data.put("parseSuccessCount", countParseStatus(start, end, OrderConstant.PARSE_STATUS_SUCCESS));
        data.put("parseFailedCount", countParseStatus(start, end, OrderConstant.PARSE_STATUS_FAILED));
        data.put("parseSkippedCount", countParseStatus(start, end, OrderConstant.PARSE_STATUS_SKIPPED));
        return Result.ok(data);
    }

    @PostMapping("/reparse")
    public Result reparse(@RequestParam String date) {
        try {
            if (!orderReparseService.canRunReparseNow()) {
                return Result.ok("当前已超过重跑时间段 " + orderReparseService.getRuntimeWindowText() + "，不再执行重跑");
            }
            OrderReparseService.RetrySummary summary = orderReparseService.reparseDate(date);
            return Result.ok(summary.toMessage());
        } catch (Exception e) {
            log.error("重新解析失败", e);
            return Result.error(500, "重新解析失败: " + e.getMessage());
        }
    }

    @PostMapping("/retry-failed-today")
    public Result retryFailedToday() {
        try {
            OrderReparseService.RetrySummary summary = orderReparseService.retryFailedOrdersToday();
            return Result.ok(summary.toMessage());
        } catch (Exception e) {
            log.error("补偿重跑失败", e);
            return Result.error(500, "补偿重跑失败: " + e.getMessage());
        }
    }

    @PostMapping("/delete-date-data")
    public Result deleteDateData(@RequestParam String date) {
        try {
            LocalDate targetDate = LocalDate.parse(date);
            LocalDateTime start = targetDate.atStartOfDay();
            LocalDateTime end = targetDate.plusDays(1).atStartOfDay();

            LambdaQueryWrapper<OrderRaw> rawWrapper = new LambdaQueryWrapper<>();
            rawWrapper.ge(OrderRaw::getReceivedAt, start);
            rawWrapper.lt(OrderRaw::getReceivedAt, end);
            List<OrderRaw> rawList = orderRawMapper.selectList(rawWrapper);
            List<Long> rawIds = rawList.stream().map(OrderRaw::getId).toList();

            if (!rawIds.isEmpty()) {
                normalizePersistService.cleanByRawIds(rawIds, date);
            }

            log.info("reset date analysis data: date={}, rawKept={}, analysisCleaned={}",
                    date, rawIds.size(), !rawIds.isEmpty());
            return Result.ok("清理完成: date=" + date + ", 原始报单保留=" + rawIds.size() +
                    ", 已清理解析批次/解析结果/订单明细/号码统计");
        } catch (Exception e) {
            log.error("删除日期数据失败", e);
            return Result.error(500, "删除失败: " + e.getMessage());
        }
    }

    @PostMapping("/force-parse")
    public Result forceParse(@RequestParam String date) {
        try {
            OrderReparseService.RetrySummary summary = orderReparseService.reparseDateForce(date);
            return Result.ok(summary.toMessage());
        } catch (Exception e) {
            log.error("强制解析失败", e);
            return Result.error(500, "强制解析失败: " + e.getMessage());
        }
    }

    @PostMapping("/force-parse-fast")
    public Result forceParseFast(@RequestParam String date) {
        try {
            OrderReparseService.RetrySummary summary = orderReparseService.reparseDateForceFast(date);
            return Result.ok(summary.toMessage());
        } catch (Exception e) {
            log.error("高峰快速回放失败", e);
            return Result.error(500, "高峰快速回放失败: " + e.getMessage());
        }
    }

    @PostMapping("/force-parse-raw")
    public Result forceParseRaw(@RequestParam Long rawId) {
        try {
            OrderReparseService.RetrySummary summary = orderReparseService.reparseSingleRaw(rawId);
            return Result.ok(summary.toMessage());
        } catch (Exception e) {
            log.error("单条强制解析失败, rawId={}", rawId, e);
            return Result.error(500, "单条强制解析失败: " + e.getMessage());
        }
    }

    @PostMapping("/compare-raw-models")
    public Result compareRawModels(@RequestParam Long rawId,
                                   @RequestParam(required = false) String otherModel,
                                   @RequestParam(required = false, defaultValue = "true") boolean applyPostCorrections) {
        try {
            OrderRaw raw = orderRawMapper.selectById(rawId);
            if (raw == null) {
                return Result.error(404, "未找到对应原始报单");
            }

            String currentModelName = aiParseService.getDefaultModelName();
            String targetModelName = otherModel != null && !otherModel.isBlank()
                    ? otherModel.trim()
                    : compareModel;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("rawId", raw.getId());
            data.put("receivedAt", raw.getReceivedAt());
            data.put("rawText", raw.getRawText());
            data.put("applyPostCorrections", applyPostCorrections);
            data.put("currentModel", buildModelPreview(
                    aiParseService.previewParse(raw, currentModelName, applyPostCorrections)));
            data.put("otherModel", buildModelPreview(
                    aiParseService.previewParse(raw, targetModelName, applyPostCorrections)));
            return Result.ok(data);
        } catch (Exception e) {
            log.error("模型对比失败, rawId={}", rawId, e);
            return Result.error(500, "模型对比失败: " + e.getMessage());
        }
    }

    @PostMapping("/reparse-corrected-raw")
    public Result reparseCorrectedRaw(@RequestBody RawCorrectionRequest request) {
        try {
            if (request.getRawId() == null) {
                return Result.error(400, "rawId 不能为空");
            }

            boolean hasCorrectBet = request.getCorrectBet() != null && request.getCorrectBet() > 0;
            boolean hasCorrectedText = request.getCorrectedText() != null && !request.getCorrectedText().trim().isEmpty();
            if (!hasCorrectBet && !hasCorrectedText) {
                return Result.error(400, "正确注数和修正文案至少填写一项");
            }
            if (request.getCorrectBet() != null && request.getCorrectBet() <= 0) {
                return Result.error(400, "correctBet 必须大于 0");
            }
            if (hasCorrectBet && (request.getNumber() == null || !THREE_DIGIT.matcher(request.getNumber()).matches())) {
                return Result.error(400, "number 必须是 3 位数字");
            }

            OrderReparseService.RetrySummary summary = orderReparseService.reparseSingleRaw(request.getRawId(), request);
            return Result.ok(summary.toMessage());
        } catch (Exception e) {
            log.error("校正后单条重解析失败, rawId={}", request.getRawId(), e);
            return Result.error(500, "校正后单条重解析失败: " + e.getMessage());
        }
    }

    @PostMapping("/cleanup-invalid-numbers")
    public Result cleanupInvalidNumbers(@RequestParam(required = false) String issueKey) {
        try {
            LambdaQueryWrapper<AiParseResultRecord> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(AiParseResultRecord::getStatus, "SUCCESS");
            wrapper.eq(AiParseResultRecord::getValid, 1);
            if (issueKey != null && !issueKey.isBlank()) {
                wrapper.eq(AiParseResultRecord::getIssueKey, issueKey);
            }
            List<AiParseResultRecord> records = aiParseResultMapper.selectList(wrapper);

            Set<Long> invalidRawIds = new LinkedHashSet<>();
            List<Long> orphanInvalidIds = new ArrayList<>();
            int invalidRecordCount = 0;
            for (AiParseResultRecord record : records) {
                if (!hasInvalidNumbers(record)) {
                    continue;
                }
                invalidRecordCount++;
                if (record.getRawId() != null) {
                    invalidRawIds.add(record.getRawId());
                } else {
                    orphanInvalidIds.add(record.getId());
                }
                log.info("invalid parse result scheduled for cleanup: id={}, rawId={}, numbers={}, play={}",
                        record.getId(), record.getRawId(), record.getNumbers(), record.getPlay());
            }

            int orphanDeleted = 0;
            if (!orphanInvalidIds.isEmpty()) {
                for (Long id : orphanInvalidIds) {
                    aiParseResultMapper.deleteById(id);
                }
                orphanDeleted = orphanInvalidIds.size();
            }

            if (!invalidRawIds.isEmpty()) {
                normalizePersistService.cleanByRawIds(new ArrayList<>(invalidRawIds), null);
            }

            log.info("cleanup invalid numbers finished: checked={}, invalidRecords={}, cleanedRawIds={}, orphanDeleted={}",
                    records.size(), invalidRecordCount, invalidRawIds.size(), orphanDeleted);
            return Result.ok("清理完成，检查 " + records.size() + " 条记录，发现 " + invalidRecordCount +
                    " 条无效解析，清理 rawId " + invalidRawIds.size() + " 个，孤立记录删除 " + orphanDeleted + " 条");
        } catch (Exception e) {
            log.error("cleanup invalid numbers failed", e);
            return Result.error(500, "清理失败: " + e.getMessage());
        }
    }

    private boolean hasInvalidNumbers(AiParseResultRecord record) {
        if (record.getNumbers() == null || record.getNumbers().isBlank()) {
            return true;
        }
        try {
            List<String> numbers = JSON.parseArray(record.getNumbers(), String.class);
            if (numbers == null || numbers.isEmpty()) {
                return true;
            }
            for (String num : numbers) {
                if (num == null || !THREE_DIGIT.matcher(num.trim()).matches()) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    private StatsQueryRequest buildStatsRequest(String lotteryCategory,
                                               String gameType,
                                               String playType,
                                               String issueKey,
                                               String numberZone,
                                               String number,
                                               String betNumber,
                                               String messageKeyword,
                                               String sortBy,
                                               String sortOrder,
                                               Integer page,
                                               Integer pageSize,
                                               Integer topN) {
        StatsQueryRequest req = new StatsQueryRequest();
        req.setLotteryCategory(lotteryCategory);
        req.setGameType(gameType);
        req.setPlayType(playType);
        req.setIssueKey(issueKey);
        req.setNumberZone(numberZone);
        req.setNumber(number);
        req.setBetNumber(betNumber);
        req.setMessageKeyword(messageKeyword);
        req.setSortBy(sortBy);
        req.setSortOrder(sortOrder);
        req.setPage(page);
        req.setPageSize(pageSize != null ? pageSize : topN);
        req.setTopN(topN);
        return req;
    }

    private long countRawOrders(LocalDateTime start, LocalDateTime end) {
        LambdaQueryWrapper<OrderRaw> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(OrderRaw::getReceivedAt, start);
        wrapper.lt(OrderRaw::getReceivedAt, end);
        return orderRawMapper.selectCount(wrapper);
    }

    private long countParseStatus(LocalDateTime start, LocalDateTime end, int parseStatus) {
        return orderParseBatchMapper.countLatestStatusByRawReceivedAt(start, end, parseStatus);
    }

    private Map<String, Object> buildModelPreview(AiParseService.ModelPreview preview) {
        List<Map<String, Object>> records = new ArrayList<>();
        int totalOrderCount = 0;
        double totalAmount = 0D;

        for (AiParseResult result : preview.results()) {
            if (result == null || !result.isSuccess() || result.getData() == null) {
                continue;
            }
            AiParseResult.ParsedData data = result.getData();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("category", data.getCategory());
            item.put("gameType", data.getGame());
            item.put("playType", data.getPlay());
            item.put("zone", data.getZone());
            item.put("numbers", data.getNumbers());
            item.put("bet", data.getBet());
            item.put("amount", data.getAmount());
            records.add(item);

            totalOrderCount += data.getBet();
            if (data.getAmount() != null) {
                totalAmount += data.getAmount().doubleValue();
            }
        }

        records.sort(Comparator
                .comparing((Map<String, Object> item) -> String.valueOf(item.get("category")))
                .thenComparing(item -> String.valueOf(item.get("gameType")))
                .thenComparing(item -> String.valueOf(item.get("playType")))
                .thenComparing(item -> {
                    Object numbers = item.get("numbers");
                    return numbers == null ? "" : numbers.toString();
                }));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("modelName", preview.modelName());
        payload.put("parseRecordCount", records.size());
        payload.put("totalOrderCount", totalOrderCount);
        payload.put("totalAmount", totalAmount);
        payload.put("records", records);
        return payload;
    }
}
