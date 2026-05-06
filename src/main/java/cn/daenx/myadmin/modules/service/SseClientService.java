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
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
public class SseClientService {

    private static final String MESSAGE_SOURCE = "WECHAT_SSE";

    @Value("${sse.url:http://localhost:5678/events}")
    private String sseUrl;

    @Value("${sse.enabled:true}")
    private boolean sseEnabled;

    @Value("${target-group-wxid}")
    private String targetGroupWxid;

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
        if (!sseEnabled) {
            log.info("sse channel disabled");
            return;
        }
        Thread thread = new Thread(this::connectLoop, "sse-client");
        thread.setDaemon(true);
        thread.start();
        log.info("sse channel started, url={}", sseUrl);
    }

    @PreDestroy
    public void stop() {
        running = false;
    }

    private void connectLoop() {
        while (running) {
            try {
                connect();
                log.warn("sse connection closed normally, retry after 5s");
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                log.warn("sse connection failed, retry after 5s: {}", e.getMessage());
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private void connect() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(sseUrl))
                .GET()
                .build();
        HttpResponse<java.util.stream.Stream<String>> response = client.send(
                request, HttpResponse.BodyHandlers.ofLines());
        response.body().forEach(line -> {
            if (line.startsWith("data:")) {
                String json = line.substring(5).trim();
                if (!json.isEmpty()) {
                    log.info("sse payload: {}", json);
                    processMessage(json);
                }
            }
        });
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
            if (!"\u6587\u672c".equals(msg.getType())) {
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
                log.warn("sse raw message insert failed, moved to retry buffer, msgId={}, fingerprint={}",
                        msgId, rawMessage.getFingerprint());
            } else if (ingestResult == MessageIngressService.IngestResult.DUPLICATE) {
                log.info("sse duplicate raw message skipped, msgId={}, fingerprint={}",
                        msgId, rawMessage.getFingerprint());
                return;
            } else {
                log.info("sse raw message stored, id={}, msgId={}, fingerprint={}",
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
                log.debug("sse message out of order window, msgId={}, time={}", msgId, msgTime);
            }
        } catch (Exception e) {
            log.error("process sse message failed: {}", e.getMessage(), e);
        }
    }

    private String buildMsgId(SseMessageVo msg) {
        return DigestUtil.sha256Hex(buildMsgIdSeed(msg));
    }

    private String buildMsgIdSeed(SseMessageVo msg) {
        if (hasText(msg.getLocalId())) {
            return "SSE|LOCAL|" + safe(msg.getUsername()) + "|" + msg.getLocalId().trim();
        }
        if (hasText(msg.getSortSeq())) {
            return "SSE|SORT|" + safe(msg.getUsername()) + "|" + msg.getSortSeq().trim();
        }
        if (hasText(msg.getServerId())) {
            return "SSE|SERVER|" + safe(msg.getUsername()) + "|" + msg.getServerId().trim();
        }
        // Without an upstream message id, content-based de-duplication can swallow valid repeated orders.
        return "SSE|FALLBACK|" + safe(msg.getUsername()) + "|" + safe(msg.getSender()) + "|" +
                safe(msg.getTimestamp()) + "|" + safe(msg.getContent()) + "|" + UUID.randomUUID();
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
