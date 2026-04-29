package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.entity.Message;
import cn.daenx.myadmin.modules.domain.dto.OrderMessage;
import cn.daenx.myadmin.modules.domain.event.RecvMsgReqVo;
import cn.daenx.myadmin.modules.domain.event.SseMessageVo;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSONObject;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Slf4j
@Service
public class RedisClientService {

    private static final String MESSAGE_SOURCE = "WECHAT_REDIS";

    @Value("${redis.queue-name:wechat_messages}")
    private String queueName;

    @Value("${target-group-wxid}")
    private String targetGroupWxid;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MessageBufferService messageBufferService;

    @Autowired
    private MessageIngressService messageIngressService;

    @Autowired
    private OrderBufferService orderBufferService;

    @Autowired
    private OrderWindowService orderWindowService;

    private volatile boolean running = true;

    @PostConstruct
    public void start() {
        Thread thread = new Thread(this::consumeLoop, "redis-consumer");
        thread.setDaemon(true);
        thread.start();
        log.info("redis consumer started, queue={}", queueName);
    }

    @PreDestroy
    public void stop() {
        running = false;
    }

    private void consumeLoop() {
        while (running) {
            try {
                String json = redisTemplate.opsForList().rightPop(queueName, Duration.ofSeconds(5));
                if (json != null && !json.isEmpty()) {
                    log.debug("redis payload: {}", preview(json, 500));
                    processMessage(json);
                }
            } catch (Exception e) {
                log.error("redis consume failed: {}", e.getMessage(), e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }

    private void processMessage(String json) {
        try {
            SseMessageVo msg = JSONObject.parseObject(json, SseMessageVo.class);
            if (!Boolean.TRUE.equals(msg.getIsGroup())) {
                return;
            }
            if (!targetGroupWxid.equals(msg.getUsername())) {
                return;
            }
            if (!"文本".equals(msg.getType())) {
                return;
            }

            String msgId = buildMsgId(msg);

            RecvMsgReqVo vo = new RecvMsgReqVo();
            vo.setMsgId(msgId);
            vo.setTimeStamp(String.valueOf(msg.getTimestamp() * 1000L));
            vo.setFromType(2);
            vo.setMsgType(1);
            vo.setMsgSource(0);
            vo.setFromWxid(msg.getUsername());
            vo.setFinalFromWxid(msg.getSender());
            vo.setMsg(msg.getContent());

            Message rawMessage = messageIngressService.buildMessage(vo, MESSAGE_SOURCE, json);
            MessageIngressService.IngestResult ingestResult = messageIngressService.ingest(rawMessage);
            if (ingestResult == MessageIngressService.IngestResult.FAILED) {
                messageBufferService.addMessage(rawMessage);
                log.warn("redis raw message insert failed, moved to retry buffer, msgId={}, fingerprint={}",
                        msgId, rawMessage.getFingerprint());
            } else if (ingestResult == MessageIngressService.IngestResult.DUPLICATE) {
                log.info("redis duplicate raw message skipped, msgId={}, fingerprint={}",
                        msgId, rawMessage.getFingerprint());
                return;
            } else {
                log.info("redis raw message stored, id={}, msgId={}, fingerprint={}",
                        rawMessage.getId(), msgId, rawMessage.getFingerprint());
            }

            LocalDateTime msgTime = rawMessage.getReceivedAt() != null
                    ? rawMessage.getReceivedAt()
                    : LocalDateTime.now();

            if (orderWindowService.isInOrderWindow(msgTime)) {
                OrderMessage orderMsg = new OrderMessage();
                orderMsg.setMsgId(msgId);
                orderMsg.setSource("WECHAT");
                orderMsg.setFromWxid(msg.getUsername());
                orderMsg.setSenderWxid(msg.getSender());
                orderMsg.setRawText(msg.getContent());
                orderMsg.setFingerprint(messageIngressService.buildBusinessFingerprint(vo));
                orderMsg.setReceivedAt(msgTime);
                orderBufferService.add(orderMsg);
            } else {
                log.debug("redis message out of order window, msgId={}, time={}", msgId, msgTime);
            }
        } catch (Exception e) {
            log.error("process redis message failed: {}", e.getMessage(), e);
        }
    }

    private String buildMsgId(SseMessageVo msg) {
        return DigestUtil.sha256Hex(buildMsgIdSeed(msg));
    }

    private String buildMsgIdSeed(SseMessageVo msg) {
        if (hasText(msg.getLocalId())) {
            return "REDIS|LOCAL|" + safe(msg.getUsername()) + "|" + msg.getLocalId().trim();
        }
        if (hasText(msg.getSortSeq())) {
            return "REDIS|SORT|" + safe(msg.getUsername()) + "|" + msg.getSortSeq().trim();
        }
        if (hasText(msg.getServerId())) {
            return "REDIS|SERVER|" + safe(msg.getUsername()) + "|" + msg.getServerId().trim();
        }
        return "REDIS|FALLBACK|" + safe(msg.getUsername()) + "|" + safe(msg.getSender()) + "|" +
                safe(msg.getTimestamp()) + "|" + safe(msg.getContent());
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String preview(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...(truncated, length=" + value.length() + ")";
    }
}
