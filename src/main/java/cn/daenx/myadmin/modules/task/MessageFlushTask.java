package cn.daenx.myadmin.modules.task;

import cn.daenx.myadmin.entity.Message;
import cn.daenx.myadmin.modules.service.MessageBufferService;
import cn.daenx.myadmin.modules.service.MessageIngressService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class MessageFlushTask {

    @Autowired
    private MessageBufferService bufferService;

    @Autowired
    private MessageIngressService messageIngressService;

    @Scheduled(fixedRateString = "${message.flush-interval}")
    public void flush() {
        List<Message> messages = bufferService.flushAndClear();
        if (messages.isEmpty()) {
            return;
        }

        log.info("开始重试刷写消息到数据库, 数量: {}", messages.size());
        List<Message> failed = messageIngressService.batchIngest(messages);
        int successCount = messages.size() - failed.size();
        if (successCount > 0) {
            log.info("消息重试刷写成功, 数量: {}", successCount);
        }
        if (!failed.isEmpty()) {
            log.error("消息重试仍然失败, 已放回缓冲区等待下次重试, 数量: {}", failed.size());
            bufferService.restoreMessages(failed);
        }
    }
}
