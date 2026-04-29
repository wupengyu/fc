package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.entity.OrderRaw;
import cn.daenx.myadmin.modules.domain.dto.AiParseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步 AI 解析服务
 */
@Slf4j
@Service
public class AsyncParseService {

    @Autowired
    private AiParseService aiParseService;

    @Autowired
    private NormalizePersistService normalizePersistService;

    @Async("parseTaskExecutor")
    public void parseAndPersist(List<OrderRaw> rawList) {
        try {
            log.info("async ai parse started, count={}", rawList.size());
            long start = System.currentTimeMillis();

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger skipCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            aiParseService.batchParseWithCallback(rawList, (raw, results) -> {
                normalizePersistService.persistSingleWithRetry(raw, results);

                boolean hasSuccess = results.stream().anyMatch(AiParseResult::isSuccess);
                boolean hasFailed = results.stream().anyMatch(AiParseResult::isFailed);
                boolean hasSkip = results.stream().anyMatch(AiParseResult::isSkip);

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

            long elapsed = System.currentTimeMillis() - start;
            log.info("async ai parse finished: total={}, success={}, skip={}, fail={}, elapsedMs={}",
                    rawList.size(), successCount.get(), skipCount.get(), failCount.get(), elapsed);
        } catch (Exception e) {
            log.error("async ai parse failed, count={}", rawList.size(), e);
        }
    }
}
