package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.entity.NumberStats;
import cn.daenx.myadmin.mapper.NumberStatsMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class StatsRebuildService {

    @Autowired
    private NumberStatsMapper numberStatsMapper;

    @Transactional
    public Map<String, Object> rebuildIssue(String issueKey) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("issueKey", issueKey);
        data.put("beforeStatsTotals", totals(numberStatsMapper.queryIssueTotals(issueKey)));
        data.put("detailNumberTotals", totals(numberStatsMapper.queryDetailNumberTotals(issueKey)));

        int deleted = numberStatsMapper.deleteByIssueKey(issueKey);
        int inserted = numberStatsMapper.rebuildIssueFromItems(issueKey);

        int missingOrChanged = numberStatsMapper.countMissingOrChangedStatsFromDetails(issueKey);
        int orphan = numberStatsMapper.countOrphanStatsWithoutDetails(issueKey);
        data.put("deletedStatsRows", deleted);
        data.put("rebuiltStatsRows", inserted);
        data.put("afterStatsTotals", totals(numberStatsMapper.queryIssueTotals(issueKey)));
        data.put("statsMismatchCount", missingOrChanged + orphan);
        data.put("status", missingOrChanged + orphan == 0 ? "OK" : "CHECK");
        return data;
    }

    private Map<String, Object> totals(NumberStats stats) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("orderCount", stats != null && stats.getOrderCount() != null ? stats.getOrderCount() : 0);
        data.put("sumAmount", stats != null && stats.getSumAmount() != null ? stats.getSumAmount() : BigDecimal.ZERO);
        return data;
    }
}
