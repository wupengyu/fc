package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.common.constant.OrderConstant;
import cn.daenx.myadmin.entity.OrderRaw;
import cn.daenx.myadmin.mapper.OrderRawMapper;
import cn.daenx.myadmin.modules.domain.dto.OrderMessage;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class OrderIngressService {

    @Autowired
    private OrderRawMapper orderRawMapper;

    private static final long RECENT_CONTENT_TTL_SECONDS = 600L;

    private final Object ingestLock = new Object();
    private final Map<String, LocalDateTime> recentContentKeys = new LinkedHashMap<>();

    public List<OrderRaw> batchIngest(List<OrderMessage> orders) {
        List<OrderRaw> result = new ArrayList<>();
        Set<String> batchContentKeys = new HashSet<>();
        for (OrderMessage msg : orders) {
            String fingerprint = msg.getFingerprint();
            if (fingerprint == null || fingerprint.isEmpty()) {
                fingerprint = generateFingerprint(msg);
            }

            String contentKey = buildContentKey(msg);
            if (contentKey != null && !batchContentKeys.add(contentKey)) {
                log.info("duplicate order skipped within batch, senderWxid={}, receivedAt={}",
                        msg.getSenderWxid(), msg.getReceivedAt());
                continue;
            }

            synchronized (ingestLock) {
                LocalDateTime now = LocalDateTime.now();
                pruneRecentContentKeys(now);
                if (contentKey != null && recentContentKeys.containsKey(contentKey)) {
                    log.info("duplicate order skipped by recent content cache, senderWxid={}, receivedAt={}",
                            msg.getSenderWxid(), msg.getReceivedAt());
                    continue;
                }

                if (isContentDuplicate(msg)) {
                    rememberRecentContentKey(contentKey, now);
                    log.info("duplicate order skipped by content check, senderWxid={}, receivedAt={}",
                            msg.getSenderWxid(), msg.getReceivedAt());
                    continue;
                }

                OrderRaw raw = new OrderRaw();
                raw.setMsgId(msg.getMsgId());
                raw.setFingerprint(fingerprint);
                raw.setSource(resolveSource(msg));
                raw.setFromWxid(msg.getFromWxid());
                raw.setSenderWxid(msg.getSenderWxid());
                raw.setRawText(msg.getRawText());
                raw.setReceivedAt(msg.getReceivedAt());

                try {
                    orderRawMapper.insert(raw);
                    rememberRecentContentKey(contentKey, now);
                    result.add(raw);
                } catch (DuplicateKeyException e) {
                    log.info("duplicate order skipped by db unique index, fingerprint={}", fingerprint);
                } catch (Exception e) {
                    log.warn("order insert failed, fingerprint={}", fingerprint, e);
                }
            }
        }
        return result;
    }

    private boolean isContentDuplicate(OrderMessage msg) {
        if (msg.getRawText() == null || msg.getSenderWxid() == null || msg.getReceivedAt() == null) {
            return false;
        }
        LocalDateTime from = msg.getReceivedAt().minusSeconds(60);
        LocalDateTime to = msg.getReceivedAt().plusSeconds(60);
        LambdaQueryWrapper<OrderRaw> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrderRaw::getSource, resolveSource(msg));
        eqOrIsNull(wrapper, OrderRaw::getFromWxid, msg.getFromWxid());
        eqOrIsNull(wrapper, OrderRaw::getSenderWxid, msg.getSenderWxid());
        wrapper.eq(OrderRaw::getRawText, msg.getRawText());
        wrapper.between(OrderRaw::getReceivedAt, from, to);
        wrapper.last("LIMIT 1");
        return orderRawMapper.selectCount(wrapper) > 0;
    }

    private void rememberRecentContentKey(String contentKey, LocalDateTime now) {
        if (contentKey != null) {
            recentContentKeys.put(contentKey, now);
        }
    }

    private void pruneRecentContentKeys(LocalDateTime now) {
        if (recentContentKeys.isEmpty()) {
            return;
        }
        LocalDateTime cutoff = now.minusSeconds(RECENT_CONTENT_TTL_SECONDS);
        Iterator<Map.Entry<String, LocalDateTime>> iterator = recentContentKeys.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, LocalDateTime> entry = iterator.next();
            if (entry.getValue() == null || entry.getValue().isBefore(cutoff)) {
                iterator.remove();
            }
        }
    }

    private void eqOrIsNull(LambdaQueryWrapper<OrderRaw> wrapper,
                            com.baomidou.mybatisplus.core.toolkit.support.SFunction<OrderRaw, ?> column,
                            String value) {
        if (value == null) {
            wrapper.isNull(column);
        } else {
            wrapper.eq(column, value);
        }
    }

    private String buildContentKey(OrderMessage msg) {
        if (msg.getRawText() == null || msg.getReceivedAt() == null) {
            return null;
        }
        return resolveSource(msg) + "|" +
                safe(msg.getFromWxid()) + "|" +
                safe(msg.getSenderWxid()) + "|" +
                msg.getReceivedAt().truncatedTo(ChronoUnit.SECONDS) + "|" +
                msg.getRawText();
    }

    private String resolveSource(OrderMessage msg) {
        return msg.getSource() != null ? msg.getSource() : OrderConstant.SOURCE_WECHAT;
    }

    private String generateFingerprint(OrderMessage msg) {
        String seed;
        if (msg.getMsgId() != null && !msg.getMsgId().isEmpty()) {
            seed = msg.getSource() + "|" + msg.getMsgId();
        } else {
            String receivedAt = msg.getReceivedAt() != null
                    ? msg.getReceivedAt().toString()
                    : "";
            seed = safe(msg.getSource()) + "|" +
                    safe(msg.getFromWxid()) + "|" +
                    safe(msg.getSenderWxid()) + "|" +
                    safe(msg.getRawText()) + "|" +
                    receivedAt;
        }
        return DigestUtil.sha256Hex(seed);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
