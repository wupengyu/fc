package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.common.constant.OrderConstant;
import cn.daenx.myadmin.entity.OrderParseBatch;
import cn.daenx.myadmin.entity.OrderRaw;
import cn.daenx.myadmin.mapper.OrderParseBatchMapper;
import cn.daenx.myadmin.mapper.OrderRawMapper;
import cn.daenx.myadmin.modules.domain.dto.OrderMessage;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class OrderIngressService {

    @Autowired
    private OrderRawMapper orderRawMapper;

    @Autowired
    private OrderParseBatchMapper orderParseBatchMapper;

    @Value("${order.cross-source-mirror-check-enabled:false}")
    private boolean crossSourceMirrorCheckEnabled;

    private static final long RECENT_IDENTITY_TTL_SECONDS = 600L;
    private static final Set<String> DISABLED_LEGACY_ORDER_SOURCES = Set.of(
            OrderConstant.SOURCE_WECHAT,
            "WECHAT_CALLBACK"
    );
    private static final Set<String> MIRROR_SOURCES = Set.of(
            OrderConstant.SOURCE_WECHAT,
            "WECHAT_REDIS",
            "WECHAT_CALLBACK",
            "WECHAT_SSE"
    );

    private final Object ingestLock = new Object();
    private final Map<String, LocalDateTime> recentIdentityKeys = new LinkedHashMap<>();

    public List<OrderRaw> batchIngest(List<OrderMessage> orders) {
        List<OrderRaw> result = new ArrayList<>();
        List<IngestCandidate> candidates = new ArrayList<>();
        Set<String> batchIdentityKeys = new HashSet<>();
        Map<String, String> batchMirrorSources = new LinkedHashMap<>();
        for (OrderMessage msg : orders) {
            String fingerprint = generateFingerprint(msg);
            String source = resolveSource(msg);
            if (isDisabledLegacyWechatSource(source)) {
                log.info("legacy WECHAT order source skipped because callback channel is disabled, senderWxid={}, receivedAt={}",
                        msg.getSenderWxid(), msg.getReceivedAt());
                continue;
            }

            String identityKey = fingerprint != null && !fingerprint.isBlank() ? "FP|" + fingerprint : null;
            if (identityKey != null && !batchIdentityKeys.add(identityKey)) {
                log.info("duplicate order skipped within batch by message identity, senderWxid={}, receivedAt={}",
                        msg.getSenderWxid(), msg.getReceivedAt());
                continue;
            }

            String exactMirrorKey = buildExactMirrorKey(msg, source);
            if (exactMirrorKey != null) {
                String existingSource = batchMirrorSources.putIfAbsent(exactMirrorKey, source);
                if (existingSource != null && !existingSource.equals(source)) {
                    log.info("cross-source mirror order skipped within batch, source={}, existingSource={}, senderWxid={}, receivedAt={}",
                            source, existingSource, msg.getSenderWxid(), msg.getReceivedAt());
                    continue;
                }
            }

            candidates.add(new IngestCandidate(msg, fingerprint, source, identityKey));
        }

        Set<String> existingFingerprints = loadExistingFingerprints(candidates);
        synchronized (ingestLock) {
            LocalDateTime now = LocalDateTime.now();
            pruneRecentIdentityKeys(now);
            for (IngestCandidate candidate : candidates) {
                OrderMessage msg = candidate.message();
                String identityKey = candidate.identityKey();
                String fingerprint = candidate.fingerprint();
                String source = candidate.source();

                if (identityKey != null && recentIdentityKeys.containsKey(identityKey)) {
                    log.info("duplicate order skipped by recent message identity cache, senderWxid={}, receivedAt={}",
                            msg.getSenderWxid(), msg.getReceivedAt());
                    continue;
                }

                if (existingFingerprints.contains(fingerprint)) {
                    rememberRecentIdentityKey(identityKey, now);
                    OrderRaw existingRaw = findByFingerprint(fingerprint);
                    resumeExistingRawWithoutBatch(existingRaw, result, "message identity fingerprint");
                    log.info("duplicate order skipped by message identity fingerprint, fingerprint={}", fingerprint);
                    continue;
                }

                OrderRaw mirrorDuplicate = findExactCrossSourceMirrorDuplicate(msg, source);
                if (mirrorDuplicate != null) {
                    rememberRecentIdentityKey(identityKey, now);
                    resumeExistingRawWithoutBatch(mirrorDuplicate, result, "cross-source mirror");
                    log.info("cross-source mirror order skipped by raw identity, source={}, senderWxid={}, receivedAt={}",
                            source, msg.getSenderWxid(), msg.getReceivedAt());
                    continue;
                }

                OrderRaw raw = buildRaw(msg, fingerprint, source);

                try {
                    orderRawMapper.insert(raw);
                    rememberRecentIdentityKey(identityKey, now);
                    existingFingerprints.add(fingerprint);
                    result.add(raw);
                } catch (DuplicateKeyException e) {
                    rememberRecentIdentityKey(identityKey, now);
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

    private OrderRaw findExactCrossSourceMirrorDuplicate(OrderMessage msg, String source) {
        if (!crossSourceMirrorCheckEnabled || !isMirrorSource(source)
                || msg.getReceivedAt() == null || isBlank(msg.getRawText())) {
            return null;
        }
        LambdaQueryWrapper<OrderRaw> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(OrderRaw::getSource, MIRROR_SOURCES);
        wrapper.ne(OrderRaw::getSource, source);
        wrapper.notIn(OrderRaw::getSource, DISABLED_LEGACY_ORDER_SOURCES);
        eqOrIsNull(wrapper, OrderRaw::getFromWxid, msg.getFromWxid());
        eqOrIsNull(wrapper, OrderRaw::getSenderWxid, msg.getSenderWxid());
        wrapper.eq(OrderRaw::getRawText, msg.getRawText());
        wrapper.eq(OrderRaw::getReceivedAt, msg.getReceivedAt());
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

    private void rememberRecentIdentityKey(String identityKey, LocalDateTime now) {
        if (identityKey != null) {
            recentIdentityKeys.put(identityKey, now);
        }
    }

    private void pruneRecentIdentityKeys(LocalDateTime now) {
        if (recentIdentityKeys.isEmpty()) {
            return;
        }
        LocalDateTime cutoff = now.minusSeconds(RECENT_IDENTITY_TTL_SECONDS);
        Iterator<Map.Entry<String, LocalDateTime>> iterator = recentIdentityKeys.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, LocalDateTime> entry = iterator.next();
            if (entry.getValue() == null || entry.getValue().isBefore(cutoff)) {
                iterator.remove();
            }
        }
    }

    private String resolveSource(OrderMessage msg) {
        if (msg.getSource() == null || msg.getSource().isBlank()) {
            return OrderConstant.SOURCE_WECHAT;
        }
        return msg.getSource().trim().toUpperCase(Locale.ROOT);
    }

    private String buildExactMirrorKey(OrderMessage msg, String source) {
        if (!isMirrorSource(source) || msg.getReceivedAt() == null || isBlank(msg.getRawText())) {
            return null;
        }
        return safe(msg.getFromWxid()) + "|" +
                safe(msg.getSenderWxid()) + "|" +
                msg.getReceivedAt() + "|" +
                msg.getRawText();
    }

    private boolean isMirrorSource(String source) {
        return source != null && MIRROR_SOURCES.contains(source);
    }

    private boolean isDisabledLegacyWechatSource(String source) {
        return DISABLED_LEGACY_ORDER_SOURCES.contains(source);
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

    private void eqOrIsNull(LambdaQueryWrapper<OrderRaw> wrapper,
                            SFunction<OrderRaw, ?> column,
                            String value) {
        if (value == null) {
            wrapper.isNull(column);
        } else {
            wrapper.eq(column, value);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record IngestCandidate(OrderMessage message,
                                   String fingerprint,
                                   String source,
                                   String identityKey) {
    }
}
