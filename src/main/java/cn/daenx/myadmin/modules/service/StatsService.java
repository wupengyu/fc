package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.entity.AiParseResultRecord;
import cn.daenx.myadmin.entity.NumberStats;
import cn.daenx.myadmin.entity.OrderRaw;
import cn.daenx.myadmin.mapper.AiParseResultMapper;
import cn.daenx.myadmin.mapper.NumberStatsMapper;
import cn.daenx.myadmin.mapper.OrderRawMapper;
import cn.daenx.myadmin.modules.domain.dto.StatsQueryRequest;
import cn.daenx.myadmin.modules.domain.vo.NumberStatsVO;
import cn.daenx.myadmin.modules.domain.vo.StatsVO;
import cn.daenx.myadmin.modules.domain.vo.TopSourceMessagePanelVO;
import cn.daenx.myadmin.modules.domain.vo.TopSourceParseRecordVO;
import cn.daenx.myadmin.modules.domain.vo.TopSourceMessageVO;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class StatsService {

    @Autowired
    private AiParseResultMapper aiParseResultMapper;

    @Autowired
    private OrderRawMapper orderRawMapper;

    @Autowired
    private NumberStatsMapper numberStatsMapper;

    @Value("${stats.default-top-n:20}")
    private int defaultTopN;

    @Value("${stats.max-top-n:500}")
    private int maxTopN;

    public StatsVO queryTopN(StatsQueryRequest req) {
        int groupedCount = countNumberStats(req);
        if (groupedCount > 0 || aiParseResultMapper.countSuccessByIssue(
                req.getIssueKey(),
                req.getLotteryCategory(),
                req.getGameType()
        ) == 0) {
            return buildStatsFromNumberStats(req, groupedCount);
        }

        List<AiParseResultRecord> records = loadFilteredRecords(req);
        return buildStatsFromRecords(req, records);
    }

    public TopSourceMessagePanelVO queryTopSourceMessages(StatsQueryRequest req) {
        TopSourceMessagePanelVO panel = new TopSourceMessagePanelVO();
        panel.setLotteryCategory(req.getLotteryCategory());
        panel.setGameType(req.getGameType());
        panel.setPlayType(req.getPlayType());
        panel.setIssueKey(req.getIssueKey());
        panel.setRawId(req.getRawId());
        panel.setNumberZone(req.getNumberZone());
        panel.setBetNumber(req.getBetNumber());
        panel.setMessageKeyword(req.getMessageKeyword());
        panel.setSortBy("messageTimeDesc");

        Set<Long> matchedRawIds = collectMatchedSourceRawIds(req);
        List<Long> matchedRawIdList = new ArrayList<>(matchedRawIds);
        String messageKeyword = normalize(req.getMessageKeyword());

        int pageSize = resolvePageSize(req.getPageSize());
        int currentPage = resolvePage(req.getPage());
        int totalItems = resolveMatchedRawCount(matchedRawIdList, messageKeyword);
        int totalPages = resolveTotalPages(totalItems, pageSize);
        currentPage = Math.min(currentPage, totalPages);
        int offset = (currentPage - 1) * pageSize;
        List<OrderRaw> pageRaws = queryMatchedRawPage(matchedRawIdList, messageKeyword, offset, pageSize);
        Map<Long, OrderRaw> rawMap = new HashMap<>();
        List<Long> pageRawIds = new ArrayList<>();
        for (OrderRaw raw : pageRaws) {
            rawMap.put(raw.getId(), raw);
            pageRawIds.add(raw.getId());
        }
        List<AiParseResultRecord> pageRecords = loadLatestRecordsByRawIds(req.getIssueKey(), new LinkedHashSet<>(pageRawIds));

        panel.setPage(currentPage);
        panel.setPageSize(pageSize);
        panel.setTotalItems(totalItems);
        panel.setTotalPages(totalPages);
        panel.setItems(buildTopSourceMessages(pageRawIds, rawMap, pageRecords, offset));
        panel.setQueryTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return panel;
    }

    private List<AiParseResultRecord> loadFilteredRecords(StatsQueryRequest req) {
        return aiParseResultMapper.queryLatestSuccessByIssue(
                req.getIssueKey(),
                req.getLotteryCategory(),
                req.getGameType(),
                normalizePlayType(req.getPlayType()),
                normalize(req.getNumberZone()),
                normalize(req.getBetNumber())
        );
    }

    private List<AiParseResultRecord> loadLatestRecordsByRawIds(String issueKey, Set<Long> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return Collections.emptyList();
        }
        return aiParseResultMapper.queryLatestSuccessByIssueAndRawIds(issueKey, new ArrayList<>(rawIds));
    }

    private Set<Long> collectMatchedSourceRawIds(StatsQueryRequest req) {
        Long rawIdFilter = req.getRawId();
        String betNumber = normalize(req.getBetNumber());

        if (rawIdFilter != null && betNumber == null) {
            return new LinkedHashSet<>(Collections.singleton(rawIdFilter));
        }

        List<Long> rawIds;
        if (betNumber != null) {
            if (rawIdFilter != null) {
                List<AiParseResultRecord> records = loadLatestRecordsByRawIds(
                        req.getIssueKey(),
                        new LinkedHashSet<>(Collections.singleton(rawIdFilter))
                );
                rawIds = new ArrayList<>();
                for (AiParseResultRecord record : records) {
                    if (matchesBetNumber(record, betNumber)) {
                        rawIds.add(rawIdFilter);
                        break;
                    }
                }
            } else {
                rawIds = aiParseResultMapper.queryLatestSuccessRawIdsByIssueAndBetNumber(req.getIssueKey(), betNumber);
            }
        } else {
            rawIds = aiParseResultMapper.queryLatestSuccessRawIdsByIssue(req.getIssueKey());
        }

        if (rawIds == null || rawIds.isEmpty()) {
            return Collections.emptySet();
        }

        return new LinkedHashSet<>(rawIds);
    }

    private int resolveMatchedRawCount(List<Long> rawIds, String messageKeyword) {
        if (rawIds == null || rawIds.isEmpty()) {
            return 0;
        }
        if (messageKeyword == null) {
            return rawIds.size();
        }
        return orderRawMapper.countByRawIdsAndKeyword(rawIds, messageKeyword);
    }

    private List<OrderRaw> queryMatchedRawPage(List<Long> rawIds, String messageKeyword, int offset, int pageSize) {
        if (rawIds == null || rawIds.isEmpty()) {
            return Collections.emptyList();
        }
        return orderRawMapper.queryPageByRawIdsAndKeyword(rawIds, messageKeyword, offset, pageSize);
    }

    private List<AiParseResultRecord> filterRecordsByRawIds(List<AiParseResultRecord> records, Set<Long> rawIds) {
        if (records == null || records.isEmpty() || rawIds == null || rawIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<AiParseResultRecord> filtered = new ArrayList<>();
        for (AiParseResultRecord record : records) {
            if (record.getRawId() != null && rawIds.contains(record.getRawId())) {
                filtered.add(record);
            }
        }
        return filtered;
    }

    private StatsVO buildStatsFromRecords(StatsQueryRequest req, List<AiParseResultRecord> records) {
        String numberFilter = normalize(req.getNumber());
        Map<String, int[]> betCountMap = new LinkedHashMap<>();
        Map<String, BigDecimal> amountMap = new LinkedHashMap<>();
        Map<String, String> zoneMap = new LinkedHashMap<>();

        int totalBets = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (AiParseResultRecord record : records) {
            List<String> numbers = parseNumbers(record.getNumbers());
            if (numbers.isEmpty()) {
                continue;
            }

            int bet = resolveBet(record);
            BigDecimal amount = resolveAmount(record);
            BigDecimal allocPerNumber = resolveAmountPerNumber(amount, numbers.size());
            String zone = normalize(record.getZone());
            if (zone == null) {
                zone = "MAIN";
            }

            boolean matched = false;
            for (String rawNumber : numbers) {
                String number = normalize(rawNumber);
                if (number == null) {
                    continue;
                }
                if (numberFilter != null && !numberFilter.equals(number)) {
                    continue;
                }
                matched = true;
                betCountMap.computeIfAbsent(number, key -> new int[]{0})[0] += bet;
                amountMap.merge(number, allocPerNumber, BigDecimal::add);
                zoneMap.putIfAbsent(number, zone);
            }
            if (numberFilter == null) {
                totalBets += bet;
                totalAmount = totalAmount.add(amount);
            } else if (matched) {
                totalBets += bet;
                totalAmount = totalAmount.add(allocPerNumber);
            }
        }

        boolean sortByAmount = "amount".equals(req.getSortBy());
        boolean sortAsc = "ASC".equals(resolveSortDirection(req.getSortOrder()));
        List<Map.Entry<String, int[]>> sorted = new ArrayList<>(betCountMap.entrySet());
        sorted.sort((left, right) -> {
            if (sortByAmount) {
                int compare = amountMap.getOrDefault(right.getKey(), BigDecimal.ZERO)
                        .compareTo(amountMap.getOrDefault(left.getKey(), BigDecimal.ZERO));
                return sortAsc ? -compare : compare;
            }
            int compare = Integer.compare(right.getValue()[0], left.getValue()[0]);
            return sortAsc ? -compare : compare;
        });

        int pageSize = resolvePageSize(req.getPageSize());
        int currentPage = resolvePage(req.getPage());
        int totalItems = sorted.size();
        int totalPages = resolveTotalPages(totalItems, pageSize);
        currentPage = Math.min(currentPage, totalPages);
        int offset = (currentPage - 1) * pageSize;

        List<NumberStatsVO> items = new ArrayList<>();
        int limit = Math.min(offset + pageSize, sorted.size());
        for (int i = offset; i < limit; i++) {
            Map.Entry<String, int[]> entry = sorted.get(i);
            NumberStatsVO item = new NumberStatsVO();
            item.setRank(i + 1);
            item.setNumberZone(zoneMap.getOrDefault(entry.getKey(), "MAIN"));
            item.setNumber(entry.getKey());
            item.setOrderCount(entry.getValue()[0]);
            item.setSumAmount(amountMap.getOrDefault(entry.getKey(), BigDecimal.ZERO));
            items.add(item);
        }

        StatsVO vo = new StatsVO();
        vo.setLotteryCategory(req.getLotteryCategory());
        vo.setGameType(req.getGameType());
        vo.setPlayType(req.getPlayType() != null ? req.getPlayType() : "ALL");
        vo.setIssueKey(req.getIssueKey());
        vo.setNumber(numberFilter);
        vo.setSortOrder(resolveSortDirection(req.getSortOrder()));
        vo.setPage(currentPage);
        vo.setPageSize(pageSize);
        vo.setTotalItems(totalItems);
        vo.setTotalPages(totalPages);
        vo.setTotalOrders(totalBets);
        vo.setTotalAmount(totalAmount);
        vo.setQueryTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        vo.setStats(items);
        return vo;
    }

    private StatsVO buildStatsFromNumberStats(StatsQueryRequest req, int groupedCount) {
        String playType = normalizePlayType(req.getPlayType());
        String numberZone = normalize(req.getNumberZone());
        String number = normalize(req.getNumber());
        String sortColumn = resolveStatsSortColumn(req.getSortBy());
        String sortDirection = resolveSortDirection(req.getSortOrder());
        int pageSize = resolvePageSize(req.getPageSize());
        int currentPage = resolvePage(req.getPage());
        int totalItems = Math.max(groupedCount, 0);
        int totalPages = resolveTotalPages(totalItems, pageSize);
        currentPage = Math.min(currentPage, totalPages);
        int offset = (currentPage - 1) * pageSize;

        List<NumberStatsVO> items = new ArrayList<>();
        if (totalItems > 0) {
            List<NumberStats> stats = numberStatsMapper.queryPage(
                    req.getLotteryCategory(),
                    req.getGameType(),
                    req.getIssueKey(),
                    playType,
                    numberZone,
                    number,
                    sortColumn,
                    sortDirection,
                    offset,
                    pageSize
            );
            for (int i = 0; i < stats.size(); i++) {
                NumberStats row = stats.get(i);
                NumberStatsVO item = new NumberStatsVO();
                item.setRank(offset + i + 1);
                item.setNumberZone(normalize(row.getNumberZone()) != null ? row.getNumberZone() : "MAIN");
                item.setNumber(row.getNumber());
                item.setOrderCount(row.getOrderCount() != null ? row.getOrderCount() : 0);
                item.setSumAmount(row.getSumAmount() != null ? row.getSumAmount() : BigDecimal.ZERO);
                items.add(item);
            }
        }

        NumberStats totals = numberStatsMapper.queryTotals(
                req.getLotteryCategory(),
                req.getGameType(),
                req.getIssueKey(),
                playType,
                numberZone,
                number
        );

        StatsVO vo = new StatsVO();
        vo.setLotteryCategory(req.getLotteryCategory());
        vo.setGameType(req.getGameType());
        vo.setPlayType(req.getPlayType() != null ? req.getPlayType() : "ALL");
        vo.setIssueKey(req.getIssueKey());
        vo.setNumber(number);
        vo.setSortOrder(sortDirection);
        vo.setPage(currentPage);
        vo.setPageSize(pageSize);
        vo.setTotalItems(totalItems);
        vo.setTotalPages(totalPages);
        vo.setTotalOrders(totals != null && totals.getOrderCount() != null ? totals.getOrderCount() : 0);
        vo.setTotalAmount(totals != null && totals.getSumAmount() != null ? totals.getSumAmount() : BigDecimal.ZERO);
        vo.setQueryTime(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        vo.setStats(items);
        return vo;
    }

    private List<TopSourceMessageVO> buildTopSourceMessages(List<Long> orderedRawIds,
                                                            Map<Long, OrderRaw> rawMap,
                                                            List<AiParseResultRecord> records,
                                                            int offset) {
        if (orderedRawIds == null || orderedRawIds.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, List<AiParseResultRecord>> recordsByRaw = new LinkedHashMap<>();
        if (records != null) {
            for (AiParseResultRecord record : records) {
                Long rawId = record.getRawId();
                if (rawId == null) {
                    continue;
                }
                recordsByRaw.computeIfAbsent(rawId, ignored -> new ArrayList<>()).add(record);
            }
        }

        List<TopSourceMessageVO> items = new ArrayList<>();
        for (int i = 0; i < orderedRawIds.size(); i++) {
            Long rawId = orderedRawIds.get(i);
            List<AiParseResultRecord> rawRecords = recordsByRaw.get(rawId);
            if (rawRecords == null || rawRecords.isEmpty()) {
                continue;
            }
            TopSourceMessageVO item = buildMessageItem(rawId, rawMap.get(rawId), rawRecords);
            item.setRank(offset + i + 1);
            items.add(item);
        }
        return items;
    }

    private Map<Long, OrderRaw> loadOrderRawMap(Set<Long> rawIds) {
        if (rawIds == null || rawIds.isEmpty()) {
            return Collections.emptyMap();
        }

        LambdaQueryWrapper<OrderRaw> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(OrderRaw::getId, rawIds);

        Map<Long, OrderRaw> rawMap = new HashMap<>();
        for (OrderRaw raw : orderRawMapper.selectList(wrapper)) {
            rawMap.put(raw.getId(), raw);
        }
        return rawMap;
    }

    private Set<Long> collectRawIds(List<AiParseResultRecord> records) {
        Set<Long> rawIds = new HashSet<>();
        for (AiParseResultRecord record : records) {
            if (record.getRawId() != null) {
                rawIds.add(record.getRawId());
            }
        }
        return rawIds;
    }

    private boolean matchesMessageKeyword(OrderRaw raw, String keyword) {
        if (keyword == null) {
            return true;
        }
        if (raw == null || raw.getRawText() == null) {
            return false;
        }
        return raw.getRawText().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    private boolean matchesBetNumber(AiParseResultRecord record, String betNumber) {
        if (betNumber == null) {
            return true;
        }
        return parseNumbers(record.getNumbers()).contains(betNumber);
    }

    private TopSourceMessageVO buildMessageItem(Long rawId,
                                                OrderRaw raw,
                                                List<AiParseResultRecord> records) {
        List<AiParseResultRecord> sortedRecords = new ArrayList<>(records);
        sortedRecords.sort((left, right) -> {
            int byItemIndex = Integer.compare(
                    left.getItemIndex() != null ? left.getItemIndex() : Integer.MAX_VALUE,
                    right.getItemIndex() != null ? right.getItemIndex() : Integer.MAX_VALUE
            );
            if (byItemIndex != 0) {
                return byItemIndex;
            }

            long leftId = left.getId() != null ? left.getId() : Long.MAX_VALUE;
            long rightId = right.getId() != null ? right.getId() : Long.MAX_VALUE;
            return Long.compare(leftId, rightId);
        });

        List<TopSourceParseRecordVO> parseRecords = new ArrayList<>();
        int totalOrderCount = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (AiParseResultRecord record : sortedRecords) {
            parseRecords.add(buildParseRecord(record));
            totalOrderCount += resolveBet(record);
            totalAmount = totalAmount.add(resolveAmount(record));
        }

        TopSourceMessageVO item = new TopSourceMessageVO();
        item.setRawId(rawId);
        item.setReceivedAt(resolveReceivedAt(raw, sortedRecords));
        item.setRawText(raw != null ? raw.getRawText() : null);
        item.setParseRecordCount(parseRecords.size());
        item.setTotalOrderCount(totalOrderCount);
        item.setTotalAmount(totalAmount);
        item.setParseRecords(parseRecords);
        return item;
    }

    private TopSourceParseRecordVO buildParseRecord(AiParseResultRecord record) {
        TopSourceParseRecordVO item = new TopSourceParseRecordVO();
        item.setParseResultId(record.getId());
        item.setItemIndex(record.getItemIndex());
        item.setCategory(record.getCategory());
        item.setGameType(record.getGame());
        item.setPlayType(record.getPlay());
        item.setNumberZone(normalize(record.getZone()) != null ? normalize(record.getZone()) : "MAIN");
        item.setNumbers(parseNumbers(record.getNumbers()));
        item.setBet(resolveBet(record));
        item.setAmount(resolveAmount(record));
        item.setStatus(record.getStatus());
        item.setReason(record.getReason());
        return item;
    }

    private LocalDateTime resolveReceivedAt(OrderRaw raw, List<AiParseResultRecord> records) {
        if (raw != null) {
            if (raw.getReceivedAt() != null) {
                return raw.getReceivedAt();
            }
            if (raw.getCreatedAt() != null) {
                return raw.getCreatedAt();
            }
        }

        LocalDateTime fallback = null;
        for (AiParseResultRecord record : records) {
            if (record.getCreatedAt() == null) {
                continue;
            }
            if (fallback == null || record.getCreatedAt().isAfter(fallback)) {
                fallback = record.getCreatedAt();
            }
        }
        return fallback;
    }

    private int resolveTopN(Integer topN) {
        int value = topN != null ? topN : defaultTopN;
        return Math.min(value, maxTopN);
    }

    private int resolvePage(Integer page) {
        return page != null && page > 0 ? page : 1;
    }

    private int resolvePageSize(Integer pageSize) {
        int value = pageSize != null ? pageSize : defaultTopN;
        value = Math.max(1, value);
        return Math.min(value, maxTopN);
    }

    private int resolveTotalPages(int totalItems, int pageSize) {
        return Math.max(1, (int) Math.ceil(totalItems / (double) pageSize));
    }

    private int countNumberStats(StatsQueryRequest req) {
        return numberStatsMapper.countGrouped(
                req.getLotteryCategory(),
                req.getGameType(),
                req.getIssueKey(),
                normalizePlayType(req.getPlayType()),
                normalize(req.getNumberZone()),
                normalize(req.getNumber())
        );
    }

    private String resolveStatsSortColumn(String sortBy) {
        return "amount".equalsIgnoreCase(normalize(sortBy)) ? "sum_amount" : "order_count";
    }

    private String resolveSortDirection(String sortOrder) {
        return "asc".equalsIgnoreCase(normalize(sortOrder)) ? "ASC" : "DESC";
    }

    private int resolveBet(AiParseResultRecord record) {
        return record.getBet() != null && record.getBet() > 0 ? record.getBet() : 1;
    }

    private BigDecimal resolveAmount(AiParseResultRecord record) {
        return record.getAmount() != null ? record.getAmount() : BigDecimal.ZERO;
    }

    private BigDecimal resolveAmountPerNumber(BigDecimal amount, int numberSize) {
        if (numberSize <= 0) {
            return BigDecimal.ZERO;
        }
        return amount.divide(BigDecimal.valueOf(numberSize), 2, RoundingMode.HALF_UP);
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

    @SuppressWarnings("unchecked")
    private List<String> parseNumbers(String numbersJson) {
        if (numbersJson == null || numbersJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return JSON.parseArray(numbersJson, String.class);
        } catch (Exception e) {
            log.warn("解析 numbers JSON 失败: {}", numbersJson, e);
            return Collections.emptyList();
        }
    }
}
