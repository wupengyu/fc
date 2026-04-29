package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.modules.domain.dto.OrderMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class OrderDetector {

    private final OrderTextNormalizationService orderTextNormalizationService;

    public OrderDetector(OrderTextNormalizationService orderTextNormalizationService) {
        this.orderTextNormalizationService = orderTextNormalizationService;
    }

    @Value("${detector.keywords:双色球,大乐透,3D,排列三,排列五,七乐彩,福彩,体彩}")
    private String keywordsConfig;

    private static final Pattern PATTERN_ORDER = Pattern.compile(
            "\\d+.*?(单|组|米|倍|注|元|块|dan|zu|mi|bei|zhu|yuan)", Pattern.CASE_INSENSITIVE
    );

    private static final Pattern PATTERN_3D = Pattern.compile(
            "\\d{3}.*?(单|组|米|倍|元|dan|zu|mi|bei|yuan)", Pattern.CASE_INSENSITIVE
    );

    // Match pure 3-digit number patterns like "512", "123 456"
    private static final Pattern PATTERN_3D_SIMPLE = Pattern.compile(
            "\\b\\d{3}\\b"
    );

    public List<OrderMessage> filter(List<OrderMessage> messages) {
        return messages.stream()
                .filter(this::isLikelyOrder)
                .collect(Collectors.toList());
    }

    private boolean isLikelyOrder(OrderMessage msg) {
        String text = orderTextNormalizationService.normalizeForParsing(msg.getRawText());
        if (text == null || text.isBlank()) {
            return false;
        }
        if (containsKeyword(text)) return true;
        if (PATTERN_ORDER.matcher(text).find()) return true;
        if (PATTERN_3D.matcher(text).find()) return true;
        if (PATTERN_3D_SIMPLE.matcher(text).find()) return true;
        return false;
    }

    private boolean containsKeyword(String text) {
        String[] keywords = keywordsConfig.split(",");
        for (String kw : keywords) {
            if (text.contains(orderTextNormalizationService.normalizeForParsing(kw.trim()))) {
                return true;
            }
        }
        return false;
    }
}
