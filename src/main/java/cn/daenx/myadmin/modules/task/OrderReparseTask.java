package cn.daenx.myadmin.modules.task;

import cn.daenx.myadmin.modules.service.OrderReparseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OrderReparseTask {

    @Autowired
    private OrderReparseService orderReparseService;

    @Scheduled(fixedDelayString = "${order.reparse-schedule-delay:60000}")
    public void retryFailedOrders() {
        OrderReparseService.RetrySummary summary = orderReparseService.retryFailedOrdersToday();
        if (summary.executed()) {
            log.info("自动补偿重跑完成: 扫描={}, 重跑={}, 成功={}, 跳过={}, 失败={}",
                    summary.scannedCount(),
                    summary.retryCount(),
                    summary.successCount(),
                    summary.skipCount(),
                    summary.failCount());
        }
    }
}
