package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.common.constant.OrderConstant;
import cn.daenx.myadmin.entity.OrderParseBatch;
import cn.daenx.myadmin.entity.OrderRaw;
import cn.daenx.myadmin.mapper.OrderParseBatchMapper;
import cn.daenx.myadmin.mapper.OrderRawMapper;
import cn.daenx.myadmin.modules.domain.dto.OrderMessage;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class OrderIngressService {

    @Autowired
    private OrderRawMapper orderRawMapper;

    @Autowired
    private OrderParseBatchMapper orderParseBatchMapper;

    private static final Set<String> ALLOWED_ORDER_SOURCES = Set.of(
            OrderConstant.SOURCE_WECHAT_REDIS
    );

    private final Object ingestLock = new Object();

    public List<OrderRaw> batchIngest(List<OrderMessage> orders) {
        List<OrderRaw> result = new ArrayList<>();
        List<IngestCandidate> candidates = new ArrayList<>();
        Set<String> batchFingerprints = new HashSet<>();
        for (OrderMessage msg : orders) {
            String source = resolveSource(msg);
            if (!isAllowedOrderSource(source)) {
                log.warn("non-redis order source rejected, source={}, senderWxid={}, receivedAt={}",
                        source, msg.getSenderWxid(), msg.getReceivedAt());
                continue;
            }

            String fingerprint = generateFingerprint(msg);
            if (!batchFingerprints.add(fingerprint)) {
                log.info("duplicate order skipped within batch by message identity, senderWxid={}, receivedAt={}",
                        msg.getSenderWxid(), msg.getReceivedAt());
                continue;
            }

            candidates.add(new IngestCandidate(msg, fingerprint, source));
        }

        Set<String> existingFingerprints = loadExistingFingerprints(candidates);
        synchronized (ingestLock) {
            for (IngestCandidate candidate : candidates) {
                OrderMessage msg = candidate.message();
                String fingerprint = candidate.fingerprint();
                String source = candidate.source();

                if (existingFingerprints.contains(fingerprint)) {
                    OrderRaw existingRaw = findByFingerprint(fingerprint);
                    resumeExistingRawWithoutBatch(existingRaw, result, "message identity fingerprint");
                    log.info("duplicate order skipped by message identity fingerprint, fingerprint={}", fingerprint);
                    continue;
                }

                OrderRaw raw = buildRaw(msg, fingerprint, source);

                try {
                    orderRawMapper.insert(raw);
                    existingFingerprints.add(fingerprint);
                    result.add(raw);
                } catch (DuplicateKeyException e) {
                    OrderRaw existingRaw = findByFingerprint(fingerprint);
                    resumeExistingRawWithoutBatch(existingRaw, result, "db unique index");
                    log.info("duplicate order skipped by db unique index, fingerprint={}", fingerprint);
                } catch (Exception e) {
                    log.warn("order insert failed, fingerprint={}", fingerprint, e);
                    throw new IllegalStateException("order insert failed", e);
                }
            }
        }
        return result;
    }

    private Set<String> loadExistingFingerprints(List<IngestCandidate> candidates) {
        List<String> fingerprints = candidates.stream()
                .map(IngestCandidate::fingerprint)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        if (fingerprints.isEmpty()) {
            return new HashSet<>();
        }
        LambdaQueryWrapper<OrderRaw> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(OrderRaw::getFingerprint, fingerprints);
        Set<String> existing = new HashSet<>();
        for (OrderRaw raw : orderRawMapper.selectList(wrapper)) {
            if (raw.getFingerprint() != null) {
                existing.add(raw.getFingerprint());
            }
        }
        return existing;
    }

    private OrderRaw findByFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return null;
        }
        LambdaQueryWrapper<OrderRaw> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderRaw::getFingerprint, fingerprint);
        wrapper.last("LIMIT 1");
        List<OrderRaw> rows = orderRawMapper.selectList(wrapper);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void resumeExistingRawWithoutBatch(OrderRaw existingRaw, List<OrderRaw> result, String reason) {
        if (existingRaw == null || existingRaw.getId() == null || hasAnyParseBatch(existingRaw.getId())) {
            return;
        }
        result.add(existingRaw);
        log.warn("existing duplicate raw has no parse batch, resume parsing: rawId={}, reason={}",
                existingRaw.getId(), reason);
    }

    private boolean hasAnyParseBatch(Long rawId) {
        LambdaQueryWrapper<OrderParseBatch> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderParseBatch::getRawId, rawId);
        wrapper.last("LIMIT 1");
        return orderParseBatchMapper.selectCount(wrapper) > 0;
    }

    private OrderRaw buildRaw(OrderMessage msg, String fingerprint, String source) {
        OrderRaw raw = new OrderRaw();
        raw.setMsgId(msg.getMsgId());
        raw.setFingerprint(fingerprint);
        raw.setSource(source);
        raw.setFromWxid(msg.getFromWxid());
        raw.setSenderWxid(msg.getSenderWxid());
        raw.setRawText(msg.getRawText());
        raw.setReceivedAt(msg.getReceivedAt());
        return raw;
    }

    private String resolveSource(OrderMessage msg) {
        if (msg.getSource() == null || msg.getSource().isBlank()) {
            return OrderConstant.SOURCE_WECHAT;
        }
        return msg.getSource().trim().toUpperCase(Locale.ROOT);
    }

    private boolean isAllowedOrderSource(String source) {
        return source != null && ALLOWED_ORDER_SOURCES.contains(source);
    }

    private String generateFingerprint(OrderMessage msg) {
        String source = safe(resolveSource(msg));
        String seed;
        if (msg.getMsgId() != null && !msg.getMsgId().isEmpty()) {
            seed = source + "|" + msg.getMsgId();
        } else {
            // Missing message id must not fall back to content de-duplication.
            // Same text sent twice is still two valid raw orders.
            seed = source + "|" +
                    safe(msg.getFromWxid()) + "|" +
                    safe(msg.getSenderWxid()) + "|" +
                    UUID.randomUUID();
        }
        return DigestUtil.sha256Hex(seed);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record IngestCandidate(OrderMessage message,
                                   String fingerprint,
                                   String source) {
    }
}
