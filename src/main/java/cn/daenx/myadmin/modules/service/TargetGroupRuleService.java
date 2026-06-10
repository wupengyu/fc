package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.entity.Message;
import cn.daenx.myadmin.entity.OrderRaw;
import cn.daenx.myadmin.mapper.MessageMapper;
import cn.daenx.myadmin.mapper.OrderRawMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TargetGroupRuleService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final MessageMapper messageMapper;
    private final OrderRawMapper orderRawMapper;
    private final OrderTextNormalizationService orderTextNormalizationService;

    @Value("${target-group-wxid:}")
    private String targetGroupWxid;

    @Value("${order.fc-stop.enabled:true}")
    private boolean fcStopEnabled;

    @Value("${order.fc-stop.start:21:16:00}")
    private String fcStopStartText;

    @Value("${order.fc-stop.end:21:35:00}")
    private String fcStopEndText;

    @Value("${order.fc-stop.keyword:福停}")
    private String fcStopKeyword;

    @Value("${order.fc-stop.force-from-start:true}")
    private boolean fcStopForceFromStart;

    @Value("${order.fc-stop.cache-seconds:30}")
    private long cacheSeconds;

    private final Map<LocalDate, CachedStopSignal> stopSignalCache = new ConcurrentHashMap<>();
    private final Object stopSignalLock = new Object();

    public TargetGroupRuleService(MessageMapper messageMapper,
                                  OrderRawMapper orderRawMapper,
                                  OrderTextNormalizationService orderTextNormalizationService) {
        this.messageMapper = messageMapper;
        this.orderRawMapper = orderRawMapper;
        this.orderTextNormalizationService = orderTextNormalizationService;
    }

    public boolean shouldForceTc(OrderRaw raw) {
        if (!fcStopEnabled || raw == null || raw.getReceivedAt() == null || !isTargetGroup(raw.getFromWxid())) {
            return false;
        }

        LocalDateTime configuredStartAt = raw.getReceivedAt().toLocalDate().atTime(getFcStopStart());
        if (fcStopForceFromStart && raw.getReceivedAt().isAfter(configuredStartAt)) {
            return true;
        }

        LocalDateTime stopAt = resolveFcStopAt(raw.getReceivedAt().toLocalDate());
        return stopAt != null && raw.getReceivedAt().isAfter(stopAt);
    }

    public String buildAiHint(OrderRaw raw) {
        if (!shouldForceTc(raw)) {
            return "";
        }

        LocalTime stopStart = getFcStopStart();
        LocalTime stopEnd = getFcStopEnd();
        return """
                [群聊业务规则]
                这条消息来自设定的目标群。该群配置为当晚 %s 起执行“转体彩”规则，当前消息时间晚于该时间。
                因此从该时间之后到当晚 %s 收单结束，后续报单一律按体彩排列三处理。
                即使消息正文里出现“福彩”“3D”“福”等福彩字样，也必须全部忽略，仍然按体彩排列三处理，不得按福彩3D处理。
                """.formatted(
                TIME_FORMATTER.format(stopStart),
                TIME_FORMATTER.format(stopEnd)
        ).trim();
    }

    public boolean isTargetGroup(String fromWxid) {
        return targetGroupWxid != null
                && !targetGroupWxid.isBlank()
                && targetGroupWxid.equals(normalize(fromWxid));
    }

    private LocalDateTime resolveFcStopAt(LocalDate date) {
        if (date == null) {
            return null;
        }

        CachedStopSignal cached = stopSignalCache.get(date);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.loadedAtMillis() <= cacheSeconds * 1000L) {
            return cached.stopAt();
        }

        synchronized (stopSignalLock) {
            cached = stopSignalCache.get(date);
            now = System.currentTimeMillis();
            if (cached != null && now - cached.loadedAtMillis() <= cacheSeconds * 1000L) {
                return cached.stopAt();
            }

            LocalDateTime stopAt = loadFcStopAt(date);
            stopSignalCache.put(date, new CachedStopSignal(stopAt, now));
            return stopAt;
        }
    }

    private LocalDateTime loadFcStopAt(LocalDate date) {
        LocalDateTime from = date.atTime(getFcStopStart());
        LocalDateTime to = date.atTime(getFcStopEnd());

        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Message::getFromWxid, targetGroupWxid);
        wrapper.ge(Message::getReceivedAt, from);
        wrapper.le(Message::getReceivedAt, to);
        wrapper.orderByAsc(Message::getReceivedAt);

        LocalDateTime stopAt = null;

        List<Message> messages = messageMapper.selectList(wrapper);
        if (messages != null) {
            for (Message message : messages) {
                if (orderTextNormalizationService.containsNormalizedKeyword(message.getMsg(), fcStopKeyword)) {
                    stopAt = earliest(stopAt, message.getReceivedAt());
                    break;
                }
            }
        }

        LambdaQueryWrapper<OrderRaw> rawWrapper = new LambdaQueryWrapper<>();
        rawWrapper.eq(OrderRaw::getFromWxid, targetGroupWxid);
        rawWrapper.ge(OrderRaw::getReceivedAt, from);
        rawWrapper.le(OrderRaw::getReceivedAt, to);
        rawWrapper.orderByAsc(OrderRaw::getReceivedAt);

        List<OrderRaw> raws = orderRawMapper.selectList(rawWrapper);
        if (raws != null) {
            for (OrderRaw raw : raws) {
                if (orderTextNormalizationService.containsNormalizedKeyword(raw.getRawText(), fcStopKeyword)) {
                    stopAt = earliest(stopAt, raw.getReceivedAt());
                    break;
                }
            }
        }
        return stopAt;
    }

    private LocalDateTime earliest(LocalDateTime current, LocalDateTime candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || candidate.isBefore(current)) {
            return candidate;
        }
        return current;
    }

    private LocalTime getFcStopStart() {
        return LocalTime.parse(fcStopStartText);
    }

    private LocalTime getFcStopEnd() {
        return LocalTime.parse(fcStopEndText);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record CachedStopSignal(LocalDateTime stopAt, long loadedAtMillis) {
    }
}
