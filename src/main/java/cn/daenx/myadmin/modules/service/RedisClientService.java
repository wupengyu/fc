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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class RedisClientService {

    private static final String MESSAGE_SOURCE = "WECHAT_REDIS";

    @Value("${redis.queue-name:wechat_messages}")
    private String queueName;

    @Value("${redis.processing-queue-name:}")
    private String processingQueueName;

    @Value("${redis.consumer-threads:1}")
    private int consumerThreads;

    @Value("${redis.requeue-backoff-ms:1000}")
    private long requeueBackoffMs;

    @Value("${redis.recover-processing-on-startup:true}")
    private boolean recoverProcessingOnStartup;

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
    private final AtomicLong consumedCount = new AtomicLong();
    private final AtomicLong ackedCount = new AtomicLong();
    private final AtomicLong retriedCount = new AtomicLong();
    private final AtomicLong discardedCount = new AtomicLong();
    private final AtomicLong duplicateMessageCount = new AtomicLong();
    private final AtomicLong storedMessageCount = new AtomicLong();
    private volatile LocalDateTime lastConsumedAt;
    private volatile LocalDateTime lastAckedAt;
    private volatile LocalDateTime lastRetriedAt;

    @PostConstruct
    public void start() {
        if (recoverProcessingOnStartup) {
            recoverProcessingQueue();
        }
        int threads = Math.max(1, consumerThreads);
        for (int i = 1; i <= threads; i++) {
            Thread thread = new Thread(this::consumeLoop, "redis-consumer-" + i);
            thread.setDaemon(true);
            thread.start();
        }
        log.info("redis consumer started, queue={}, processingQueue={}, threads={}",
                queueName, processingQueue(), threads);
    }

    @PreDestroy
    public void stop() {
        running = false;
    }

    private void consumeLoop() {
        while (running) {
            try {
                String json = redisTemplate.opsForList()
                        .rightPopAndLeftPush(queueName, processingQueue(), Duration.ofSeconds(5));
                if (json != null) {
                    consumedCount.incrementAndGet();
                    lastConsumedAt = LocalDateTime.now();
                    log.debug("redis payload: {}", preview(json, 500));
                    if (json.isEmpty()) {
                        discardedCount.incrementAndGet();
                        ackProcessing(json);
                        continue;
                    }

                    ProcessResult result = processMessage(json);
                    if (result == ProcessResult.ACK) {
                        ackProcessing(json);
                    } else {
                        requeueProcessing(json);
                    }
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

    private ProcessResult processMessage(String json) {
        SseMessageVo msg;
        try {
            msg = JSONObject.parseObject(json, SseMessageVo.class);
        } catch (Exception e) {
            discardedCount.incrementAndGet();
            log.warn("discard malformed redis payload: {}", e.getMessage());
            return ProcessResult.ACK;
        }

        try {
            if (!Boolean.TRUE.equals(msg.getIsGroup())) {
                discardedCount.incrementAndGet();
                return ProcessResult.ACK;
            }
            if (!safe(targetGroupWxid).equals(safe(msg.getUsername()))) {
                discardedCount.incrementAndGet();
                return ProcessResult.ACK;
            }
            if (!"文本".equals(msg.getType())) {
                discardedCount.incrementAndGet();
                return ProcessResult.ACK;
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
                log.warn("redis raw message insert failed, will requeue payload, msgId={}, fingerprint={}",
                        msgId, rawMessage.getFingerprint());
                return ProcessResult.RETRY;
            } else if (ingestResult == MessageIngressService.IngestResult.DUPLICATE) {
                duplicateMessageCount.incrementAndGet();
                log.info("redis duplicate raw message skipped, msgId={}, fingerprint={}",
                        msgId, rawMessage.getFingerprint());
            } else {
                storedMessageCount.incrementAndGet();
                log.info("redis raw message stored, id={}, msgId={}, fingerprint={}",
                        rawMessage.getId(), msgId, rawMessage.getFingerprint());
            }

            LocalDateTime msgTime = rawMessage.getReceivedAt() != null
                    ? rawMessage.getReceivedAt()
                    : LocalDateTime.now();

            if (orderWindowService.isInOrderWindow(msgTime)) {
                OrderMessage orderMsg = new OrderMessage();
                orderMsg.setMsgId(msgId);
                orderMsg.setSource(MESSAGE_SOURCE);
                orderMsg.setFromWxid(msg.getUsername());
                orderMsg.setSenderWxid(msg.getSender());
                orderMsg.setRawText(msg.getContent());
                orderMsg.setFingerprint(messageIngressService.buildBusinessFingerprint(vo));
                orderMsg.setReceivedAt(msgTime);
                orderBufferService.add(orderMsg);
            } else {
                discardedCount.incrementAndGet();
                log.debug("redis message out of order window, msgId={}, time={}", msgId, msgTime);
            }
            return ProcessResult.ACK;
        } catch (Exception e) {
            log.error("process redis message failed: {}", e.getMessage(), e);
            return ProcessResult.RETRY;
        }
    }

    private void ackProcessing(String json) {
        Long removed = redisTemplate.opsForList().remove(processingQueue(), 1, json);
        if (removed == null || removed == 0) {
            log.warn("redis processing ack removed no payload, processingQueue={}", processingQueue());
        }
        ackedCount.incrementAndGet();
        lastAckedAt = LocalDateTime.now();
    }

    private void requeueProcessing(String json) {
        Long removed = redisTemplate.opsForList().remove(processingQueue(), 1, json);
        if (removed == null || removed == 0) {
            log.warn("redis processing requeue removed no payload, processingQueue={}", processingQueue());
        }
        redisTemplate.opsForList().leftPush(queueName, json);
        retriedCount.incrementAndGet();
        lastRetriedAt = LocalDateTime.now();
        if (requeueBackoffMs > 0) {
            try {
                Thread.sleep(requeueBackoffMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void recoverProcessingQueue() {
        try {
            String processingQueue = processingQueue();
            long recovered = 0;
            while (true) {
                String json = redisTemplate.opsForList().rightPop(processingQueue);
                if (json == null) {
                    break;
                }
                redisTemplate.opsForList().leftPush(queueName, json);
                recovered++;
            }
            if (recovered > 0) {
                retriedCount.addAndGet(recovered);
                lastRetriedAt = LocalDateTime.now();
                log.warn("redis processing queue recovered on startup, processingQueue={}, recovered={}",
                        processingQueue, recovered);
            }
        } catch (Exception e) {
            log.warn("redis processing queue recovery skipped because redis is unavailable: {}", e.getMessage());
        }
    }

    public Map<String, Object> runtimeStatus() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("queueName", queueName);
        data.put("processingQueueName", processingQueue());
        data.put("queueCount", safeQueueSize(queueName));
        data.put("processingQueueCount", safeQueueSize(processingQueue()));
        data.put("consumerThreads", Math.max(1, consumerThreads));
        data.put("consumedCount", consumedCount.get());
        data.put("ackedCount", ackedCount.get());
        data.put("retriedCount", retriedCount.get());
        data.put("discardedCount", discardedCount.get());
        data.put("duplicateMessageCount", duplicateMessageCount.get());
        data.put("storedMessageCount", storedMessageCount.get());
        data.put("lastConsumedAt", lastConsumedAt);
        data.put("lastAckedAt", lastAckedAt);
        data.put("lastRetriedAt", lastRetriedAt);
        return data;
    }

    public long queueSize() {
        return safeQueueSize(queueName);
    }

    public long processingQueueSize() {
        return safeQueueSize(processingQueue());
    }

    private long safeQueueSize(String queue) {
        try {
            Long size = redisTemplate.opsForList().size(queue);
            return size == null ? 0L : size;
        } catch (Exception e) {
            log.warn("redis queue size unavailable, queue={}, msg={}", queue, e.getMessage());
            return -1L;
        }
    }

    private String processingQueue() {
        if (processingQueueName != null && !processingQueueName.isBlank()) {
            return processingQueueName.trim();
        }
        return queueName + ":processing";
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
        // Without an upstream message id, content-based de-duplication can swallow valid repeated orders.
        return "REDIS|FALLBACK|" + safe(msg.getUsername()) + "|" + normalizeIdentity(msg.getSender()) + "|" +
                safe(msg.getTimestamp()) + "|" + normalizeContent(msg.getContent()) + "|" + UUID.randomUUID();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String normalizeContent(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
    }

    private String normalizeIdentity(String value) {
        String normalized = normalizeContent(value);
        if (normalized.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < normalized.length(); ) {
            int codePoint = normalized.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isSupplementaryCodePoint(codePoint)
                    || Character.getType(codePoint) == Character.NON_SPACING_MARK
                    || Character.isISOControl(codePoint)) {
                continue;
            }
            builder.appendCodePoint(codePoint);
        }
        return builder.toString().trim();
    }

    private String preview(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...(truncated, length=" + value.length() + ")";
    }

    private enum ProcessResult {
        ACK,
        RETRY
    }
}
