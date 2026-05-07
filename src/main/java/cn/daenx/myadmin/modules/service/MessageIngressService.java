package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.common.constant.OrderConstant;
import cn.daenx.myadmin.entity.Message;
import cn.daenx.myadmin.mapper.MessageMapper;
import cn.daenx.myadmin.modules.domain.event.RecvMsgReqVo;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class MessageIngressService {

    public enum IngestResult {
        STORED,
        DUPLICATE,
        REJECTED,
        FAILED
    }

    @Autowired
    private MessageMapper messageMapper;

    public Message buildMessage(RecvMsgReqVo vo, String source, String rawJson) {
        Message message = new Message();
        message.setMsg(vo.getMsg());
        message.setTimeStamp(normalize(vo.getTimeStamp()));
        message.setMsgId(normalize(vo.getMsgId()));
        message.setMsgType(vo.getMsgType());
        message.setMsgSource(vo.getMsgSource());
        message.setSource(source);
        message.setFromWxid(normalize(vo.getFromWxid()));
        message.setSenderWxid(normalize(vo.getFinalFromWxid()));
        message.setSignature(normalize(vo.getSignature()));
        message.setFingerprint(buildIngressFingerprint(vo, source));
        message.setRawJson(rawJson);
        message.setReceivedAt(resolveReceivedAt(vo.getTimeStamp()));
        return message;
    }

    public IngestResult ingest(Message message) {
        String source = normalize(message == null ? null : message.getSource());
        if (!OrderConstant.SOURCE_WECHAT_REDIS.equals(source)) {
            log.warn("non-redis raw message source rejected, source={}, msgId={}, senderWxid={}",
                    source, message == null ? null : message.getMsgId(),
                    message == null ? null : message.getSenderWxid());
            return IngestResult.REJECTED;
        }
        if (isDuplicate(message)) {
            return IngestResult.DUPLICATE;
        }
        try {
            messageMapper.insert(message);
            return IngestResult.STORED;
        } catch (DuplicateKeyException e) {
            return IngestResult.DUPLICATE;
        } catch (Exception e) {
            log.error("raw message insert failed, fingerprint={}", message.getFingerprint(), e);
            return IngestResult.FAILED;
        }
    }

    public List<Message> batchIngest(List<Message> messages) {
        List<Message> failed = new ArrayList<>();
        for (Message message : messages) {
            if (ingest(message) == IngestResult.FAILED) {
                failed.add(message);
            }
        }
        return failed;
    }

    public LocalDateTime resolveReceivedAt(String timeStamp) {
        if (timeStamp == null || timeStamp.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            long ts = Long.parseLong(timeStamp.trim());
            if (String.valueOf(Math.abs(ts)).length() <= 10) {
                ts = ts * 1000L;
            }
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault());
        } catch (Exception e) {
            log.warn("message timestamp parse failed, timeStamp={}", timeStamp);
            return LocalDateTime.now();
        }
    }

    public boolean isDuplicate(Message message) {
        if (message == null) {
            return false;
        }

        String source = normalize(message.getSource());
        String msgId = normalize(message.getMsgId());
        String fingerprint = normalize(message.getFingerprint());
        if (msgId == null && fingerprint == null) {
            return false;
        }

        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
        if (source != null) {
            wrapper.eq(Message::getSource, source);
        }
        if (msgId != null && fingerprint != null) {
            wrapper.and(w -> w.eq(Message::getMsgId, msgId)
                    .or()
                    .eq(Message::getFingerprint, fingerprint));
        } else if (msgId != null) {
            wrapper.eq(Message::getMsgId, msgId);
        } else {
            wrapper.eq(Message::getFingerprint, fingerprint);
        }
        wrapper.last("LIMIT 1");
        return messageMapper.selectCount(wrapper) > 0;
    }

    private String buildIngressFingerprint(RecvMsgReqVo vo, String source) {
        String msgId = normalize(vo.getMsgId());
        if (msgId != null) {
            return DigestUtil.sha256Hex(safe(source) + "|MSG_ID|" + msgId);
        }
        // No upstream id means we cannot prove two same-content messages are transport duplicates.
        // Treat them as independent raw WeChat messages so valid repeated orders are not swallowed.
        return DigestUtil.sha256Hex(safe(source) + "|NO_ID|" +
                safe(vo.getTimeStamp()) + "|" +
                safe(vo.getFromWxid()) + "|" +
                safe(vo.getFinalFromWxid()) + "|" +
                UUID.randomUUID());
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String safe(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
