package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.common.constant.OrderConstant;
import cn.daenx.myadmin.entity.*;
import cn.daenx.myadmin.mapper.*;
import cn.daenx.myadmin.modules.domain.dto.AiParseResult;
import com.alibaba.fastjson2.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 持久化服务
 * 改造后：支持新的 AiParseResult 结构，根据 valid 判断是否持久化
 */
@Slf4j
@Service
public class NormalizePersistService {

    private static final int BATCH_INSERT_SIZE = 500;

    @Value("${order.persist-max-attempts:4}")
    private int persistMaxAttempts;

    @Autowired
    private OrderParseBatchMapper parseBatchMapper;
    @Autowired
    private OrderItemMapper orderItemMapper;
    @Autowired
    private OrderItemNumberMapper orderItemNumberMapper;
    @Autowired
    private NumberStatsMapper numberStatsMapper;
    @Autowired
    private AiParseResultMapper aiParseResultMapper;
    @Autowired
    private AiCallLogMapper aiCallLogMapper;
    @Autowired
    private OrderRawMapper orderRawMapper;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Transactional
    public void batchPersistAndStat(List<OrderRaw> rawList, List<AiParseResult> results) {
        if (rawList.size() == 1) {
            // 单条消息：AI返回的所有结果（可能有多个index代表多个分组）都属于这条raw
            persistSingle(rawList.get(0), results);
            return;
        }

        // 多条消息：按index分组，index与rawList位置对应
        Map<Integer, List<AiParseResult>> grouped = results.stream()
                .collect(Collectors.groupingBy(AiParseResult::getIndex));

        for (int i = 0; i < rawList.size(); i++) {
            OrderRaw raw = rawList.get(i);
            int targetIndex = i + 1;
            List<AiParseResult> items = grouped.getOrDefault(targetIndex, Collections.emptyList());
            persistSingle(raw, items);
        }
    }

    /**
     * 持久化单条消息的解析结果（支持并行调用，每条解析完立即入库）
     */
    @Transactional
    public void persistSingle(OrderRaw raw, List<AiParseResult> items) {
        raw = lockRawForPersist(raw);
        if (items.isEmpty()) {
            log.warn("AI未返回结果: rawId={}", raw.getId());
            saveParseBatch(raw, OrderConstant.PARSE_STATUS_FAILED, "AI未返回结果", null);
            return;
        }

        String rawAiResponse = items.get(0).getRawAiResponse();

        List<AiParseResult> successItems = items.stream()
                .filter(AiParseResult::isSuccess).toList();
        List<AiParseResult> skipItems = items.stream()
                .filter(AiParseResult::isSkip).toList();
        List<AiParseResult> failedItems = items.stream()
                .filter(AiParseResult::isFailed).toList();

        OrderParseBatch batch;
        if (!successItems.isEmpty()) {
            if (hasExistingPersistData(raw)) {
                cleanSingleRaw(raw);
            }
            batch = saveParseBatch(raw, OrderConstant.PARSE_STATUS_SUCCESS, null, rawAiResponse);
        } else if (!failedItems.isEmpty()) {
            batch = saveParseBatch(raw, OrderConstant.PARSE_STATUS_FAILED,
                    failedItems.get(0).getErrorOrReason(), rawAiResponse);
        } else if (!skipItems.isEmpty()) {
            batch = saveParseBatch(raw, OrderConstant.PARSE_STATUS_SKIPPED,
                    items.get(0).getReason(), rawAiResponse);
        } else {
            batch = saveParseBatch(raw, OrderConstant.PARSE_STATUS_FAILED,
                    items.get(0).getErrorOrReason(), rawAiResponse);
        }

        String issueKey = raw.getReceivedAt() != null
                ? raw.getReceivedAt().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                : LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        int persistedIndex = 1;
        List<AiParseResultRecord> aiRecords = new ArrayList<>(items.size());
        for (AiParseResult result : items) {
            aiRecords.add(buildAiParseResultRecord(raw, batch, result, issueKey, persistedIndex++));
        }
        saveAiParseResultRecords(aiRecords);

        if (!successItems.isEmpty()) {
            persistValidOrders(raw, successItems, batch);
        }
    }

    public void persistSingleWithRetry(OrderRaw raw, List<AiParseResult> items) {
        int maxAttempts = Math.max(1, persistMaxAttempts);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                transactionTemplate.executeWithoutResult(status -> persistSingle(raw, items));
                return;
            } catch (RuntimeException e) {
                if (!isRetryableLockException(e) || attempt >= maxAttempts) {
                    throw e;
                }
                long waitMs = Math.min(300L * attempt, 1500L);
                log.warn("persist single retry after db lock, rawId={}, attempt={}/{}, waitMs={}, msg={}",
                        raw != null ? raw.getId() : null,
                        attempt,
                        maxAttempts,
                        waitMs,
                        rootMessage(e));
                sleepQuietly(waitMs);
            }
        }
    }

    /**
     * 持久化有效报单（支持同一条消息多个解析结果）
     */
    private void persistValidOrders(OrderRaw raw, List<AiParseResult> successItems, OrderParseBatch batch) {
        int itemNo = 1;
        Set<ItemContributionKey> seenContributions = new HashSet<>();
        int skippedDuplicateNumbers = 0;
        List<PendingValidOrder> pendingOrders = new ArrayList<>();
        for (AiParseResult result : successItems) {
            AiParseResult.ParsedData data = result.getData();
            if (data == null) {
                log.warn("解析成功但data为空: rawId={}, itemNo={}", raw.getId(), itemNo);
                itemNo++;
                continue;
            }

            List<String> numbers = data.getNumbers();
            if (numbers == null || numbers.isEmpty()) {
                log.warn("解析成功但号码为空: rawId={}, itemNo={}", raw.getId(), itemNo);
                itemNo++;
                continue;
            }

            List<String> cleanedNumbers = numbers.stream()
                    .filter(Objects::nonNull)
                    .toList();
            if (cleanedNumbers.isEmpty()) {
                log.warn("解析成功但有效号码为空: rawId={}, itemNo={}", raw.getId(), itemNo);
                itemNo++;
                continue;
            }

            String playType = data.getPlay() != null ? data.getPlay() : "直选";
            String zone = data.getZone() != null ? data.getZone() : OrderConstant.ZONE_MAIN;
            int betCount = resolvePositiveInt(data.getBet(), 1);
            BigDecimal allocPerNumber = BigDecimal.ZERO;
            if (data.getAmount() != null && data.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                allocPerNumber = data.getAmount().divide(BigDecimal.valueOf(cleanedNumbers.size()), 2, RoundingMode.HALF_UP);
            }

            List<String> effectiveNumbers = new ArrayList<>(cleanedNumbers.size());
            for (String number : cleanedNumbers) {
                ItemContributionKey contributionKey = new ItemContributionKey(
                        data.getCategory(),
                        data.getGame(),
                        playType,
                        zone,
                        number,
                        betCount
                );
                if (!seenContributions.add(contributionKey)) {
                    skippedDuplicateNumbers++;
                    continue;
                }
                effectiveNumbers.add(number);
            }

            if (effectiveNumbers.isEmpty()) {
                itemNo++;
                continue;
            }

            BigDecimal effectiveAmount = allocPerNumber.multiply(BigDecimal.valueOf(effectiveNumbers.size()));

            List<String> statNumbers = effectiveNumbers.stream()
                    .sorted(Comparator.naturalOrder())
                    .toList();

            OrderItem item = buildOrderItemFromParsedData(raw, batch, data, itemNo, effectiveAmount);
            pendingOrders.add(new PendingValidOrder(item, effectiveNumbers, statNumbers, zone, allocPerNumber));
            itemNo++;
        }

        persistPendingOrders(raw, pendingOrders);

        if (skippedDuplicateNumbers > 0) {
            log.info("duplicate parsed numbers skipped before stats, rawId={}, skipped={}",
                    raw.getId(), skippedDuplicateNumbers);
        }
    }

    private void persistPendingOrders(OrderRaw raw, List<PendingValidOrder> pendingOrders) {
        if (pendingOrders.isEmpty()) {
            return;
        }

        List<OrderItem> orderItems = pendingOrders.stream()
                .map(PendingValidOrder::item)
                .toList();
        saveOrderItems(orderItems);

        List<OrderItemNumber> allItemNumbers = new ArrayList<>();
        Map<StatKey, StatDelta> statDeltaMap = new LinkedHashMap<>();
        for (PendingValidOrder pendingOrder : pendingOrders) {
            OrderItem item = pendingOrder.item();
            if (item.getId() == null) {
                throw new IllegalStateException("批量保存订单项后未返回ID: rawId=" + raw.getId()
                        + ", itemNo=" + item.getItemNo());
            }

            for (String number : pendingOrder.effectiveNumbers()) {
                allItemNumbers.add(buildOrderItemNumber(item.getId(),
                        pendingOrder.zone(),
                        number,
                        pendingOrder.allocPerNumber()));
            }

            for (String number : pendingOrder.statNumbers()) {
                StatKey key = new StatKey(
                        item.getLotteryCategory(),
                        item.getGameType(),
                        item.getPlayType(),
                        item.getIssueKey(),
                        pendingOrder.zone(),
                        number
                );
                statDeltaMap.computeIfAbsent(key, ignored -> new StatDelta())
                        .merge(item.getBetCount(), pendingOrder.allocPerNumber());
            }
        }

        saveOrderItemNumbers(allItemNumbers);
        applyStatDeltas(statDeltaMap);
    }

    private void applyStatDeltas(Map<StatKey, StatDelta> statDeltaMap) {
        if (statDeltaMap.isEmpty()) {
            return;
        }

        List<Map.Entry<StatKey, StatDelta>> entries = new ArrayList<>(statDeltaMap.entrySet());
        entries.sort(Comparator
                .comparing((Map.Entry<StatKey, StatDelta> entry) -> safeSort(entry.getKey().lotteryCategory()))
                .thenComparing(entry -> safeSort(entry.getKey().gameType()))
                .thenComparing(entry -> safeSort(entry.getKey().playType()))
                .thenComparing(entry -> safeSort(entry.getKey().issueKey()))
                .thenComparing(entry -> safeSort(entry.getKey().numberZone()))
                .thenComparing(entry -> safeSort(entry.getKey().number())));

        List<NumberStats> stats = new ArrayList<>(entries.size());
        for (Map.Entry<StatKey, StatDelta> entry : entries) {
            stats.add(buildNumberStatsDelta(entry.getKey(), entry.getValue()));
        }
        upsertStatsBatch(stats);
    }

    private String safeSort(String value) {
        return value == null ? "" : value;
    }

    /**
     * 从新的 ParsedData 结构保存订单项
     */
    private OrderItem buildOrderItemFromParsedData(OrderRaw raw, OrderParseBatch batch, AiParseResult.ParsedData data,
                                                   int itemNo, BigDecimal effectiveAmount) {
        OrderItem item = new OrderItem();
        item.setRawId(raw.getId());
        item.setBatchId(batch.getId());
        item.setItemNo(itemNo);
        item.setLotteryCategory(data.getCategory());
        item.setGameType(data.getGame());
        item.setPlayType(data.getPlay() != null ? data.getPlay() : "直选");
        // issueKey 从消息接收时间推导
        item.setIssueKey(raw.getReceivedAt() != null
                ? raw.getReceivedAt().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                : LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE));
        item.setBetCount(resolvePositiveInt(data.getBet(), 1));
        item.setGroupCount(1);  // 新结构中没有groupCount，默认为1
        item.setMultiple(resolvePositiveInt(data.getMultiple(), 1));
        item.setTotalAmount(effectiveAmount != null ? effectiveAmount : BigDecimal.ZERO);
        item.setAmountAllocMode(OrderConstant.ALLOC_SPLIT);
        item.setRawText(raw.getRawText());
        item.setStatApplied(OrderConstant.STAT_APPLIED);
        return item;
    }

    private void saveOrderItems(List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (int i = 0; i < items.size(); i += BATCH_INSERT_SIZE) {
            int end = Math.min(i + BATCH_INSERT_SIZE, items.size());
            List<OrderItem> batch = items.subList(i, end);
            if (batch.size() == 1) {
                orderItemMapper.insert(batch.get(0));
            } else {
                orderItemMapper.insertBatchRecords(batch);
            }
        }
    }

    private int resolvePositiveInt(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private OrderRaw lockRawForPersist(OrderRaw raw) {
        if (raw == null || raw.getId() == null) {
            return raw;
        }
        OrderRaw lockedRaw = orderRawMapper.selectByIdForUpdate(raw.getId());
        return lockedRaw != null ? lockedRaw : raw;
    }

    private OrderParseBatch saveParseBatch(OrderRaw raw, int status, String msg, String rawAiResponse) {
        OrderParseBatch batch = new OrderParseBatch();
        batch.setRawId(raw.getId());
        batch.setParseVersion("v3");
        batch.setParseStatus(status);
        batch.setParseMsg(msg);
        batch.setAiResponseBody(rawAiResponse);
        int effective = (status == OrderConstant.PARSE_STATUS_SUCCESS) ? OrderConstant.EFFECTIVE : OrderConstant.NOT_EFFECTIVE;
        batch.setIsEffective(effective);
        parseBatchMapper.insert(batch);
        if (effective == OrderConstant.EFFECTIVE && batch.getId() != null && raw.getId() != null) {
            parseBatchMapper.markOthersNotEffective(raw.getId(), batch.getId());
        }
        return batch;
    }

    private OrderItemNumber buildOrderItemNumber(Long itemId, String zone, String number, BigDecimal allocAmount) {
        OrderItemNumber n = new OrderItemNumber();
        n.setItemId(itemId);
        n.setNumberZone(zone);
        n.setNumber(number);
        n.setAmountAlloc(allocAmount);
        return n;
    }

    private void saveOrderItemNumbers(List<OrderItemNumber> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            return;
        }
        for (int i = 0; i < numbers.size(); i += BATCH_INSERT_SIZE) {
            int end = Math.min(i + BATCH_INSERT_SIZE, numbers.size());
            orderItemNumberMapper.insertBatchRecords(numbers.subList(i, end));
        }
    }

    /**
     * 保存AI解析结果记录到 t_ai_parse_result
     */
    private AiParseResultRecord buildAiParseResultRecord(OrderRaw raw, OrderParseBatch batch, AiParseResult result, String issueKey, int itemIndex) {
        AiParseResultRecord record = new AiParseResultRecord();
        record.setRawId(raw.getId());
        record.setBatchId(batch.getId());
        record.setItemIndex(itemIndex);
        record.setValid(result.isSuccess() ? 1 : 0);
        record.setStatus(result.getStatus());
        record.setReason(result.getErrorOrReason());
        record.setIssueKey(issueKey);

        AiParseResult.ParsedData data = result.getData();
        if (data != null) {
            record.setCategory(data.getCategory());
            record.setGame(data.getGame());
            record.setPlay(data.getPlay());
            record.setZone(data.getZone() != null ? data.getZone() : OrderConstant.ZONE_MAIN);
            record.setNumbers(data.getNumbers() != null ? JSON.toJSONString(data.getNumbers()) : null);
            record.setBet(resolvePositiveInt(data.getBet(), 1));
            record.setMultiple(resolvePositiveInt(data.getMultiple(), 1));
            record.setAmount(data.getAmount() != null ? data.getAmount() : BigDecimal.ZERO);
        } else {
            record.setBet(0);
            record.setMultiple(1);
            record.setAmount(BigDecimal.ZERO);
        }

        return record;
    }

    private void saveAiParseResultRecords(List<AiParseResultRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (int i = 0; i < records.size(); i += BATCH_INSERT_SIZE) {
            int end = Math.min(i + BATCH_INSERT_SIZE, records.size());
            aiParseResultMapper.insertBatchRecords(records.subList(i, end));
        }
    }

    @Transactional
    public void cleanSingleRaw(OrderRaw raw) {
        if (raw == null || raw.getId() == null) {
            return;
        }
        Long rawId = raw.getId();
        List<Long> rawIds = List.of(rawId);
        List<Long> batchIds = selectBatchIdsByRawIds(rawIds);

        List<OrderItem> items = selectItemsByRawIdsOrBatchIds(rawIds, batchIds);

        if (!items.isEmpty()) {
            decrementAppliedStats(items);
            deleteItemsWithNumbers(items);
        }

        LambdaQueryWrapper<OrderParseBatch> batchWrapper = new LambdaQueryWrapper<>();
        batchWrapper.eq(OrderParseBatch::getRawId, rawId);
        parseBatchMapper.delete(batchWrapper);

        int aiDeleted = deleteAiResultsByRawIdsOrBatchIds(rawIds, batchIds);

        String issueKey = raw.getReceivedAt() != null
                ? raw.getReceivedAt().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                : null;
        if (issueKey != null) {
            String userMessage = "请解析以下消息：\n[1] " + raw.getRawText();
            LambdaQueryWrapper<AiCallLog> logWrapper = new LambdaQueryWrapper<>();
            logWrapper.eq(AiCallLog::getIssueKey, issueKey);
            logWrapper.eq(AiCallLog::getUserMessage, userMessage);
            aiCallLogMapper.delete(logWrapper);
        }

        numberStatsMapper.deleteNonPositiveRows();
        log.info("清除单条解析数据: rawId={}, batchIds={}, itemRows={}, aiRows={}",
                rawId, batchIds.size(), items.size(), aiDeleted);
    }

    private boolean hasExistingPersistData(OrderRaw raw) {
        if (raw == null || raw.getId() == null) {
            return false;
        }
        LambdaQueryWrapper<OrderParseBatch> batchWrapper = new LambdaQueryWrapper<>();
        batchWrapper.eq(OrderParseBatch::getRawId, raw.getId());
        if (parseBatchMapper.selectCount(batchWrapper) > 0) {
            return true;
        }

        LambdaQueryWrapper<OrderItem> itemWrapper = new LambdaQueryWrapper<>();
        itemWrapper.eq(OrderItem::getRawId, raw.getId());
        if (orderItemMapper.selectCount(itemWrapper) > 0) {
            return true;
        }

        LambdaQueryWrapper<AiParseResultRecord> aiWrapper = new LambdaQueryWrapper<>();
        aiWrapper.eq(AiParseResultRecord::getRawId, raw.getId());
        return aiParseResultMapper.selectCount(aiWrapper) > 0;
    }

    private List<Long> selectBatchIdsByRawIds(List<Long> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<OrderParseBatch> batchWrapper = new LambdaQueryWrapper<>();
        batchWrapper.in(OrderParseBatch::getRawId, rawIds);
        return parseBatchMapper.selectList(batchWrapper).stream()
                .map(OrderParseBatch::getId)
                .filter(Objects::nonNull)
                .toList();
    }

    private List<OrderItem> selectItemsByRawIdsOrBatchIds(List<Long> rawIds, List<Long> batchIds) {
        if ((rawIds == null || rawIds.isEmpty()) && (batchIds == null || batchIds.isEmpty())) {
            return Collections.emptyList();
        }
        LambdaQueryWrapper<OrderItem> itemWrapper = new LambdaQueryWrapper<>();
        itemWrapper.and(wrapper -> {
            boolean hasRawIds = rawIds != null && !rawIds.isEmpty();
            boolean hasBatchIds = batchIds != null && !batchIds.isEmpty();
            if (hasRawIds) {
                wrapper.in(OrderItem::getRawId, rawIds);
            }
            if (hasBatchIds) {
                if (hasRawIds) {
                    wrapper.or();
                }
                wrapper.in(OrderItem::getBatchId, batchIds);
            }
        });
        return orderItemMapper.selectList(itemWrapper);
    }

    private void decrementAppliedStats(List<OrderItem> items) {
        List<OrderItem> appliedItems = items == null ? Collections.emptyList() : items.stream()
                .filter(item -> item.getId() != null)
                .filter(item -> item.getStatApplied() != null && item.getStatApplied() == OrderConstant.STAT_APPLIED)
                .toList();
        if (appliedItems.isEmpty()) {
            return;
        }

        Map<Long, OrderItem> itemById = appliedItems.stream()
                .collect(Collectors.toMap(OrderItem::getId, item -> item, (left, right) -> left));
        List<Long> itemIds = new ArrayList<>(itemById.keySet());
        List<OrderItemNumber> itemNumbers = new ArrayList<>();
        for (int i = 0; i < itemIds.size(); i += BATCH_INSERT_SIZE) {
            int end = Math.min(i + BATCH_INSERT_SIZE, itemIds.size());
            LambdaQueryWrapper<OrderItemNumber> numQuery = new LambdaQueryWrapper<>();
            numQuery.in(OrderItemNumber::getItemId, itemIds.subList(i, end));
            itemNumbers.addAll(orderItemNumberMapper.selectList(numQuery));
        }

        Map<StatKey, StatDelta> statDeltaMap = new LinkedHashMap<>();
        for (OrderItemNumber itemNumber : itemNumbers) {
            OrderItem item = itemById.get(itemNumber.getItemId());
            if (item == null) {
                continue;
            }
            StatKey key = new StatKey(
                    item.getLotteryCategory(),
                    item.getGameType(),
                    item.getPlayType(),
                    item.getIssueKey(),
                    itemNumber.getNumberZone(),
                    itemNumber.getNumber()
            );
            int bet = item.getBetCount() != null ? item.getBetCount() : 0;
            BigDecimal amount = itemNumber.getAmountAlloc() != null
                    ? itemNumber.getAmountAlloc()
                    : BigDecimal.ZERO;
            statDeltaMap.computeIfAbsent(key, ignored -> new StatDelta()).merge(-bet, amount.negate());
        }
        applyStatDeltas(statDeltaMap);
    }

    private void deleteItemsWithNumbers(List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        List<Long> itemIds = items.stream()
                .map(OrderItem::getId)
                .filter(Objects::nonNull)
                .toList();
        if (itemIds.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<OrderItemNumber> numDelete = new LambdaQueryWrapper<>();
        numDelete.in(OrderItemNumber::getItemId, itemIds);
        orderItemNumberMapper.delete(numDelete);
        orderItemMapper.deleteBatchIds(itemIds);
    }

    private int deleteAiResultsByRawIdsOrBatchIds(List<Long> rawIds, List<Long> batchIds) {
        if ((rawIds == null || rawIds.isEmpty()) && (batchIds == null || batchIds.isEmpty())) {
            return 0;
        }
        LambdaQueryWrapper<AiParseResultRecord> aiWrapper = new LambdaQueryWrapper<>();
        aiWrapper.and(wrapper -> {
            boolean hasRawIds = rawIds != null && !rawIds.isEmpty();
            boolean hasBatchIds = batchIds != null && !batchIds.isEmpty();
            if (hasRawIds) {
                wrapper.in(AiParseResultRecord::getRawId, rawIds);
            }
            if (hasBatchIds) {
                if (hasRawIds) {
                    wrapper.or();
                }
                wrapper.in(AiParseResultRecord::getBatchId, batchIds);
            }
        });
        return aiParseResultMapper.delete(aiWrapper);
    }

    private boolean isRetryableLockException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                if (message.contains("Deadlock found when trying to get lock")
                        || message.contains("Lock wait timeout exceeded")
                        || message.contains("Connection is closed")
                        || message.contains("Communications link failure")
                        || message.contains("JDBC rollback failed")
                        || message.contains("Could not open JDBC Connection")
                        || message.contains("No operations allowed after connection closed")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record PendingValidOrder(OrderItem item,
                                     List<String> effectiveNumbers,
                                     List<String> statNumbers,
                                     String zone,
                                     BigDecimal allocPerNumber) {
    }

    private record StatKey(String lotteryCategory,
                           String gameType,
                           String playType,
                           String issueKey,
                           String numberZone,
                           String number) {
    }

    private NumberStats buildNumberStatsDelta(StatKey key, StatDelta delta) {
        NumberStats stat = new NumberStats();
        stat.setLotteryCategory(key.lotteryCategory());
        stat.setGameType(key.gameType());
        stat.setPlayType(key.playType());
        stat.setIssueKey(key.issueKey());
        stat.setNumberZone(key.numberZone());
        stat.setNumber(key.number());
        stat.setOrderCount(delta.betCount());
        stat.setSumAmount(delta.amount());
        return stat;
    }

    private void upsertStatsBatch(List<NumberStats> stats) {
        if (stats == null || stats.isEmpty()) {
            return;
        }
        for (int i = 0; i < stats.size(); i += BATCH_INSERT_SIZE) {
            int end = Math.min(i + BATCH_INSERT_SIZE, stats.size());
            numberStatsMapper.upsertStatsBatch(stats.subList(i, end));
        }
    }

    private record ItemContributionKey(String lotteryCategory,
                                       String gameType,
                                       String playType,
                                       String numberZone,
                                       String number,
                                       int betCount) {
    }

    private static final class StatDelta {
        private int betCount;
        private BigDecimal amount = BigDecimal.ZERO;

        private void merge(int deltaBetCount, BigDecimal deltaAmount) {
            betCount += deltaBetCount;
            amount = amount.add(deltaAmount == null ? BigDecimal.ZERO : deltaAmount);
        }

        private int betCount() {
            return betCount;
        }

        private BigDecimal amount() {
            return amount;
        }
    }

    /**
     * 清除指定rawId列表关联的所有解析数据（用于reparse前清理）
     */
    @Transactional
    public void cleanByRawIds(List<Long> rawIds, String issueKey) {
        if (rawIds == null || rawIds.isEmpty()) return;

        lockRawIdsForPersist(rawIds);
        List<Long> batchIds = selectBatchIdsByRawIds(rawIds);

        // 1. 查找关联的order_item。除raw_id外，也按历史batch_id兜底清理错挂明细。
        List<OrderItem> items = selectItemsByRawIdsOrBatchIds(rawIds, batchIds);

        if (!items.isEmpty()) {
            if (issueKey == null) {
                decrementAppliedStats(items);
            }

            // 2. 删除order_item_number
            // 3. 删除order_item
            deleteItemsWithNumbers(items);
        }

        // 4. 删除parse_batch
        LambdaQueryWrapper<OrderParseBatch> batchWrapper = new LambdaQueryWrapper<>();
        batchWrapper.in(OrderParseBatch::getRawId, rawIds);
        parseBatchMapper.delete(batchWrapper);

        // 5. 删除AI解析结果
        int aiDeleted = deleteAiResultsByRawIdsOrBatchIds(rawIds, batchIds);
        log.info("清除AI解析结果: rawIds.size={}, batchIds.size={}, deleted={}",
                rawIds.size(), batchIds.size(), aiDeleted);

        // 6. 删除统计数据
        if (issueKey != null) {
            int deleted = numberStatsMapper.deleteByIssueKey(issueKey);
            log.info("清除统计数据: issueKey={}, deleted={}", issueKey, deleted);
        }
        numberStatsMapper.deleteNonPositiveRows();
    }

    private void lockRawIdsForPersist(List<Long> rawIds) {
        rawIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .forEach(orderRawMapper::selectByIdForUpdate);
    }
}
