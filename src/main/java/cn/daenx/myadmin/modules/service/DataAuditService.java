package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.common.constant.OrderConstant;
import cn.daenx.myadmin.entity.NumberStats;
import cn.daenx.myadmin.entity.OrderRaw;
import cn.daenx.myadmin.mapper.AiParseResultMapper;
import cn.daenx.myadmin.mapper.MessageMapper;
import cn.daenx.myadmin.mapper.NumberStatsMapper;
import cn.daenx.myadmin.mapper.OrderParseBatchMapper;
import cn.daenx.myadmin.mapper.OrderRawMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DataAuditService {

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private OrderRawMapper orderRawMapper;

    @Autowired
    private OrderParseBatchMapper orderParseBatchMapper;

    @Autowired
    private AiParseResultMapper aiParseResultMapper;

    @Autowired
    private NumberStatsMapper numberStatsMapper;

    @Autowired
    private OrderWindowService orderWindowService;

    @Autowired
    private RedisClientService redisClientService;

    public Map<String, Object> auditDate(String dateText) {
        LocalDate date = LocalDate.parse(dateText);
        LocalDateTime dayStart = date.atStartOfDay();
        LocalDateTime dayEnd = date.plusDays(1).atStartOfDay();
        LocalDateTime orderStart = date.atTime(orderWindowService.getWindowStart());
        LocalDateTime orderEnd = date.atTime(orderWindowService.getWindowEnd());
        String issueKey = date.toString();

        List<Map<String, Object>> messageBySource = messageMapper.countBySource(dayStart, dayEnd);
        List<Map<String, Object>> rawBySource = orderRawMapper.countBySource(orderStart, orderEnd);
        List<Map<String, Object>> parseStatusBySource = orderParseBatchMapper.countLatestStatusBySource(orderStart, orderEnd);
        long redisPending = Math.max(0, redisClientService.queueSize()) + Math.max(0, redisClientService.processingQueueSize());

        int missingOrChangedStats = numberStatsMapper.countMissingOrChangedStatsFromDetails(issueKey);
        int orphanStats = numberStatsMapper.countOrphanStatsWithoutDetails(issueKey);
        int statsMismatchCount = missingOrChangedStats + orphanStats;
        int suspiciousNoExplicit3Digit = aiParseResultMapper.countSuspiciousSuccessWithoutExplicitThreeDigit(
                issueKey, orderStart, orderEnd);
        long rawWithoutBatch = orderRawMapper.countWithoutAnyBatchByReceivedAt(orderStart, orderEnd);
        long redisRawWithoutBatch = orderRawMapper.countWithoutAnyBatchByReceivedAtAndSource(
                orderStart, orderEnd, OrderConstant.SOURCE_WECHAT_REDIS);
        long rawTotal = countRaw(orderStart, orderEnd);
        long redisRawTotal = countSourceRows(rawBySource, "raw_count", OrderConstant.SOURCE_WECHAT_REDIS);

        List<String> warnings = new ArrayList<>();
        if (redisPending > 0) {
            warnings.add("Redis 仍有待处理消息: " + redisPending);
        }
        long nonRedisMessages = countNonRedisRows(messageBySource, "message_count");
        if (nonRedisMessages > 0) {
            warnings.add("原始消息表存在非 Redis 来源记录: " + nonRedisMessages);
        }
        long nonRedisRaw = countNonRedisRows(rawBySource, "raw_count");
        if (nonRedisRaw > 0) {
            warnings.add("报单原始表存在非 Redis 来源记录: " + nonRedisRaw);
        }
        if (redisRawWithoutBatch > 0) {
            warnings.add("存在未进入解析批次的 Redis 原始报单: " + redisRawWithoutBatch);
        }
        long failed = countStatus(parseStatusBySource, OrderConstant.PARSE_STATUS_FAILED, OrderConstant.SOURCE_WECHAT_REDIS);
        if (failed > 0) {
            warnings.add("存在解析失败的 Redis 报单: " + failed);
        }
        if (statsMismatchCount > 0) {
            warnings.add("号码统计表与明细表不一致: " + statsMismatchCount + " 组");
        }
        if (suspiciousNoExplicit3Digit > 0) {
            warnings.add("存在成功解析但原文无明确三位数字的可疑报单: " + suspiciousNoExplicit3Digit);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("date", issueKey);
        data.put("orderWindow", orderStart + " ~ " + orderEnd);
        data.put("messageBySource", messageBySource);
        data.put("rawTotal", rawTotal);
        data.put("redisRawTotal", redisRawTotal);
        data.put("rawBySource", rawBySource);
        data.put("rawWithoutAnyBatch", rawWithoutBatch);
        data.put("redisRawWithoutAnyBatch", redisRawWithoutBatch);
        data.put("latestParseStatusBySource", parseStatusBySource);
        data.put("multiBatchRawCount", orderParseBatchMapper.countMultiBatchRawByReceivedAt(orderStart, orderEnd));
        data.put("exactDuplicateGroupCount", orderRawMapper.countExactDuplicateGroups(orderStart, orderEnd));
        data.put("exactDuplicateSamples", orderRawMapper.sampleExactDuplicateGroups(orderStart, orderEnd, 10));
        data.put("statsTotals", totals(numberStatsMapper.queryIssueTotals(issueKey)));
        data.put("detailNumberTotals", totals(numberStatsMapper.queryDetailNumberTotals(issueKey)));
        data.put("statsTotalsByGame", numberStatsMapper.queryStatsTotalsByGame(issueKey));
        data.put("statsMismatchCount", statsMismatchCount);
        data.put("suspiciousSuccessWithoutExplicitThreeDigit", suspiciousNoExplicit3Digit);
        data.put("redisRuntime", redisClientService.runtimeStatus());
        data.put("warnings", warnings);
        data.put("status", warnings.isEmpty() ? "OK" : "CHECK");
        return data;
    }

    private long countRaw(LocalDateTime start, LocalDateTime end) {
        LambdaQueryWrapper<OrderRaw> wrapper = new LambdaQueryWrapper<>();
        wrapper.ge(OrderRaw::getReceivedAt, start);
        wrapper.lt(OrderRaw::getReceivedAt, end);
        return orderRawMapper.selectCount(wrapper);
    }

    private long countNonRedisRows(List<Map<String, Object>> rows, String countColumn) {
        long count = 0;
        for (Map<String, Object> row : rows) {
            String source = String.valueOf(row.get("source"));
            if (!OrderConstant.SOURCE_WECHAT_REDIS.equals(source)) {
                count += numberValue(row.get(countColumn));
            }
        }
        return count;
    }

    private long countSourceRows(List<Map<String, Object>> rows, String countColumn, String source) {
        long count = 0;
        for (Map<String, Object> row : rows) {
            if (source.equals(String.valueOf(row.get("source")))) {
                count += numberValue(row.get(countColumn));
            }
        }
        return count;
    }

    private long countStatus(List<Map<String, Object>> rows, int status, String source) {
        long count = 0;
        for (Map<String, Object> row : rows) {
            boolean sourceMatched = source == null || source.equals(String.valueOf(row.get("source")));
            if (sourceMatched && numberValue(row.get("parse_status")) == status) {
                count += numberValue(row.get("raw_count"));
            }
        }
        return count;
    }

    private Map<String, Object> totals(NumberStats stats) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderCount", stats != null && stats.getOrderCount() != null ? stats.getOrderCount() : 0);
        data.put("sumAmount", stats != null && stats.getSumAmount() != null ? stats.getSumAmount() : BigDecimal.ZERO);
        return data;
    }

    private long numberValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }
}
