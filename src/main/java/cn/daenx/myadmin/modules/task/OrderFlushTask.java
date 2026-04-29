package cn.daenx.myadmin.modules.task;

import cn.daenx.myadmin.entity.OrderRaw;
import cn.daenx.myadmin.modules.domain.dto.OrderMessage;
import cn.daenx.myadmin.modules.service.AsyncParseService;
import cn.daenx.myadmin.modules.service.OrderBufferService;
import cn.daenx.myadmin.modules.service.OrderIngressService;
import cn.daenx.myadmin.modules.service.OrderWindowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 报单刷写任务
 * 双触发机制：攒够 trigger-count 条，或等待 trigger-wait 毫秒，先到先触发
 * 检查频率 1 秒，flush 本身只做入库+提交异步解析，不阻塞
 */
@Slf4j
@Component
public class OrderFlushTask {

    @Autowired
    private OrderBufferService orderBufferService;

    @Autowired
    private OrderIngressService orderIngressService;

    @Autowired
    private AsyncParseService asyncParseService;

    @Autowired
    private OrderWindowService orderWindowService;

    @Value("${order.trigger-count:5}")
    private int triggerCount;

    @Value("${order.trigger-wait:5000}")
    private long triggerWait;

    @Scheduled(fixedDelay = 1000)
    public void flush() {
        try {
            OrderBufferService.DrainResult drainResult = orderBufferService.drainIfReady(triggerCount, triggerWait);
            if (!drainResult.ready()) {
                return;
            }

            List<OrderMessage> messages = drainResult.messages();
            log.info("报单缓冲区触发: 条数={}, 等待={}ms (阈值 count={}, wait={}ms)",
                    drainResult.size(), drainResult.waitingMs(), triggerCount, triggerWait);

            List<OrderMessage> inWindowMessages = messages.stream()
                    .filter(message -> orderWindowService.isInOrderWindow(message.getReceivedAt()))
                    .toList();
            int droppedCount = messages.size() - inWindowMessages.size();
            if (droppedCount > 0) {
                log.warn("dropped buffered messages outside order window, dropped={}, kept={}, window={}",
                        droppedCount, inWindowMessages.size(), orderWindowService.getWindowText());
            }
            if (inWindowMessages.isEmpty()) {
                return;
            }

            List<OrderRaw> rawList = orderIngressService.batchIngest(inWindowMessages);
            if (rawList.isEmpty()) {
                log.info("所有消息均为重复，跳过");
                return;
            }

            asyncParseService.parseAndPersist(rawList);
            log.info("已提交异步AI解析, 条数={}", rawList.size());
        } catch (Exception e) {
            log.error("报单缓冲区刷写异常，任务将在下次调度继续执行", e);
        }
    }
}
