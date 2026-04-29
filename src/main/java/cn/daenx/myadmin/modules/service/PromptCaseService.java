package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.entity.OrderRaw;
import cn.daenx.myadmin.modules.domain.dto.AiParseResult;
import cn.daenx.myadmin.modules.domain.dto.RawCorrectionRequest;
import com.alibaba.fastjson2.JSON;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class PromptCaseService {

    private static final Pattern DIGIT_TOKEN = Pattern.compile("\\d{2,4}");
    private static final List<String> FEATURE_KEYWORDS = List.of(
            "金额", "米", "元", "块", "单", "组", "复式", "直选", "体彩", "福彩",
            "不是号码", "不是下注号码", "不是注数", "缺少", "共", "各"
    );

    @Value("${ai.prompt.case.path:log/prompt-correction-cases.jsonl}")
    private String casePath;

    @Value("${ai.prompt.case.max-examples:6}")
    private int maxExamples;

    @Value("${ai.prompt.case.max-text-length:180}")
    private int maxTextLength;

    private final Object writeLock = new Object();
    private volatile long cachedFileMtime = -1L;
    private volatile List<PromptCase> cachedCases = Collections.emptyList();

    public void recordCorrectionCase(OrderRaw originalRaw,
                                     RawCorrectionRequest correction,
                                     List<AiParseResult> finalItems) {
        String correctedText = normalize(correction != null ? correction.getCorrectedText() : null);
        if (originalRaw == null || correctedText == null) {
            return;
        }

        String originalText = normalize(originalRaw.getRawText());
        if (correctedText.equals(originalText)) {
            return;
        }

        List<AiParseResult> successItems = finalItems == null ? Collections.emptyList()
                : finalItems.stream().filter(AiParseResult::isSuccess).toList();
        if (successItems.isEmpty()) {
            return;
        }

        PromptCase promptCase = new PromptCase();
        promptCase.setCreatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        promptCase.setRawId(originalRaw.getId());
        promptCase.setLotteryCategory(normalize(correction.getLotteryCategory()));
        promptCase.setGameType(normalize(correction.getGameType()));
        promptCase.setOriginalText(originalText);
        promptCase.setCorrectedText(correctedText);
        promptCase.setTargetNumber(normalize(correction.getNumber()));
        promptCase.setCorrectBet(correction.getCorrectBet());
        promptCase.setResultSummary(buildResultSummary(successItems));
        promptCase.setPatchSummary(buildPatchSummary(promptCase));

        appendCase(promptCase);
    }

    public String buildPromptPatchAppendix() {
        return buildPromptPatchAppendix(null);
    }

    public String buildPromptPatchAppendix(String rawText) {
        List<PromptCase> promptCases = selectPromptCases(rawText);
        if (promptCases.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        if (normalize(rawText) != null) {
            sb.append("\n\n# 当前消息高相关补丁\n");
            sb.append("以下补丁与当前待解析消息高度相似，优先级高于通用经验。若当前消息与案例原文相同或仅空格、标点不同，必须沿用该补丁理解方式。\n");
        } else {
            sb.append("\n\n# 近期提示词补丁\n");
            sb.append("以下补丁来自最近人工修正文案暴露出的解析漏洞。遇到相近表达时，优先遵守这些补充规则。\n");
        }

        int index = 1;
        for (PromptCase promptCase : promptCases) {
            String patchSummary = trimForPrompt(promptCase.getPatchSummary());
            if ("-".equals(patchSummary)) {
                continue;
            }
            sb.append("\n补丁").append(index++).append(": ").append(patchSummary).append("\n");
        }
        return index == 1 ? "" : sb.toString();
    }

    public String buildPromptAppendix() {
        return buildPromptAppendix(null);
    }

    public String buildPromptAppendix(String rawText) {
        List<PromptCase> promptCases = selectPromptCases(rawText);
        if (promptCases.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        if (normalize(rawText) != null) {
            sb.append("\n\n# 当前消息高相关成功案例\n");
            sb.append("以下案例与当前待解析消息结构高度相似。若当前消息与案例原文相同或仅空格、标点不同，必须直接按案例方式理解尾随数字、金额、注数和玩法。\n");
        } else {
            sb.append("\n\n# 近期修正文案成功案例\n");
            sb.append("以下案例来自人工修正文案后的成功重解析。请优先吸收这些表达模式、号码提取方式和玩法判断。\n");
        }

        int index = 1;
        for (PromptCase promptCase : promptCases) {
            sb.append("\n案例").append(index++).append(":\n");
            sb.append("原始消息: ").append(trimForPrompt(promptCase.getOriginalText())).append("\n");
            sb.append("修正文案: ").append(trimForPrompt(promptCase.getCorrectedText())).append("\n");
            sb.append("正确解析: ").append(trimForPrompt(promptCase.getResultSummary())).append("\n");
        }
        return sb.toString();
    }

    public String buildUserCaseHint(String rawText) {
        List<PromptCase> promptCases = selectPromptCases(rawText);
        if (promptCases.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[高相关历史纠偏参考]\n");
        sb.append("下面案例与当前消息高度相似，优先级高于一般经验。若当前消息与参考原文相同或仅空格、标点不同，必须按参考结果理解，不得重复犯同类误识别。\n");

        int limit = Math.min(2, promptCases.size());
        for (int i = 0; i < limit; i++) {
            PromptCase promptCase = promptCases.get(i);
            sb.append("参考").append(i + 1).append(" 原始消息: ").append(trimForPrompt(promptCase.getOriginalText())).append("\n");
            sb.append("参考").append(i + 1).append(" 修正文案: ").append(trimForPrompt(promptCase.getCorrectedText())).append("\n");
            sb.append("参考").append(i + 1).append(" 正确解析: ").append(trimForPrompt(promptCase.getResultSummary())).append("\n");
        }
        return sb.toString();
    }

    public PromptCase findExactCase(String rawText) {
        String comparableRaw = normalizeComparable(rawText);
        if (comparableRaw == null) {
            return null;
        }

        List<PromptCase> cases = deduplicateCases(loadCases());
        for (int i = cases.size() - 1; i >= 0; i--) {
            PromptCase promptCase = cases.get(i);
            if (comparableRaw.equals(normalizeComparable(promptCase.getOriginalText()))) {
                return promptCase;
            }
        }
        return null;
    }

    private void appendCase(PromptCase promptCase) {
        synchronized (writeLock) {
            try {
                Path path = Path.of(casePath);
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Files.writeString(
                        path,
                        JSON.toJSONString(promptCase) + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
                cachedFileMtime = -1L;
                log.info("prompt correction case saved: rawId={}", promptCase.getRawId());
            } catch (IOException e) {
                log.warn("save prompt correction case failed: rawId={}, msg={}", promptCase.getRawId(), e.getMessage());
            }
        }
    }

    private List<PromptCase> selectPromptCases(String rawText) {
        List<PromptCase> cases = deduplicateCases(loadCases());
        if (cases.isEmpty()) {
            return Collections.emptyList();
        }

        String normalizedRaw = normalize(rawText);
        if (normalizedRaw == null) {
            return tailCases(cases, maxExamples);
        }

        List<ScoredPromptCase> scoredCases = new ArrayList<>();
        for (PromptCase promptCase : cases) {
            int score = scoreCase(normalizedRaw, promptCase);
            if (score > 0) {
                scoredCases.add(new ScoredPromptCase(promptCase, score));
            }
        }

        if (scoredCases.isEmpty()) {
            return tailCases(cases, maxExamples);
        }

        scoredCases.sort(Comparator
                .comparingInt(ScoredPromptCase::score).reversed()
                .thenComparing(scored -> safeCreatedAt(scored.promptCase()), Comparator.reverseOrder()));

        List<PromptCase> selected = new ArrayList<>();
        for (ScoredPromptCase scoredCase : scoredCases) {
            selected.add(scoredCase.promptCase());
            if (selected.size() >= maxExamples) {
                break;
            }
        }
        return selected;
    }

    private List<PromptCase> loadCases() {
        try {
            Path path = Path.of(casePath);
            if (!Files.exists(path)) {
                return Collections.emptyList();
            }

            long fileMtime = Files.getLastModifiedTime(path).toMillis();
            if (fileMtime == cachedFileMtime && !cachedCases.isEmpty()) {
                return cachedCases;
            }

            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            List<PromptCase> parsedCases = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    PromptCase promptCase = JSON.parseObject(line, PromptCase.class);
                    if (promptCase != null) {
                        parsedCases.add(promptCase);
                    }
                } catch (Exception e) {
                    log.warn("skip bad prompt correction case line: {}", e.getMessage());
                }
            }

            cachedCases = parsedCases;
            cachedFileMtime = fileMtime;
            return cachedCases;
        } catch (IOException e) {
            log.warn("load prompt correction cases failed: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private List<PromptCase> deduplicateCases(List<PromptCase> cases) {
        if (cases == null || cases.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, PromptCase> deduped = new LinkedHashMap<>();
        for (PromptCase promptCase : cases) {
            String key = buildCaseKey(promptCase);
            deduped.remove(key);
            deduped.put(key, promptCase);
        }
        return new ArrayList<>(deduped.values());
    }

    private String buildCaseKey(PromptCase promptCase) {
        if (promptCase == null) {
            return "null";
        }
        if (promptCase.getRawId() != null) {
            return "raw:" + promptCase.getRawId();
        }
        return "text:" + nullSafe(promptCase.getOriginalText()) + "|" + nullSafe(promptCase.getCorrectedText());
    }

    private List<PromptCase> tailCases(List<PromptCase> cases, int limit) {
        if (cases == null || cases.isEmpty()) {
            return Collections.emptyList();
        }
        int fromIndex = Math.max(0, cases.size() - Math.max(1, limit));
        return new ArrayList<>(cases.subList(fromIndex, cases.size()));
    }

    private int scoreCase(String rawText, PromptCase promptCase) {
        String originalText = normalize(promptCase.getOriginalText());
        String correctedText = normalize(promptCase.getCorrectedText());
        if (originalText == null && correctedText == null) {
            return 0;
        }

        int score = 0;
        String comparableRaw = normalizeComparable(rawText);
        String comparableOriginal = normalizeComparable(originalText);
        if (comparableRaw != null && comparableRaw.equals(comparableOriginal)) {
            score += 1000;
        }

        Set<String> rawDigits = extractDigitTokens(rawText);
        Set<String> caseDigits = new LinkedHashSet<>();
        caseDigits.addAll(extractDigitTokens(originalText));
        caseDigits.addAll(extractDigitTokens(correctedText));
        for (String digit : rawDigits) {
            if (caseDigits.contains(digit)) {
                score += digit.length() == 3 ? 8 : 4;
            }
        }

        String combinedCaseText = (nullSafe(originalText) + " " + nullSafe(correctedText)).toLowerCase(Locale.ROOT);
        String normalizedRaw = rawText.toLowerCase(Locale.ROOT);
        for (String keyword : FEATURE_KEYWORDS) {
            String lowerKeyword = keyword.toLowerCase(Locale.ROOT);
            if (normalizedRaw.contains(lowerKeyword) && combinedCaseText.contains(lowerKeyword)) {
                score += 3;
            }
        }

        if (correctedText != null && correctedText.contains("金额") && rawText.contains("组")) {
            score += 6;
        }
        if (correctedText != null && (correctedText.contains("不是号码") || correctedText.contains("不是下注号码"))) {
            score += 5;
        }
        if (originalText != null && rawText.contains("复式") == originalText.contains("复式")) {
            score += 2;
        }
        if (originalText != null && rawText.contains("体") == originalText.contains("体")) {
            score += 1;
        }
        if (originalText != null && rawText.contains("福") == originalText.contains("福")) {
            score += 1;
        }
        return score;
    }

    private Set<String> extractDigitTokens(String text) {
        String normalized = normalize(text);
        if (normalized == null) {
            return Collections.emptySet();
        }
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = DIGIT_TOKEN.matcher(normalized);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    private String safeCreatedAt(PromptCase promptCase) {
        return promptCase == null || promptCase.getCreatedAt() == null ? "" : promptCase.getCreatedAt();
    }

    private String normalizeComparable(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return null;
        }
        return normalized
                .replaceAll("[\\s,，。．、:：;；\\-—_]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private String buildResultSummary(List<AiParseResult> successItems) {
        return successItems.stream()
                .map(this::formatSuccessItem)
                .filter(item -> item != null && !item.isBlank())
                .collect(Collectors.joining(" | "));
    }

    private String buildPatchSummary(PromptCase promptCase) {
        String correctedText = normalize(promptCase.getCorrectedText());
        String resultSummary = normalize(promptCase.getResultSummary());
        String targetNumber = normalize(promptCase.getTargetNumber());
        Integer correctBet = promptCase.getCorrectBet();

        StringBuilder sb = new StringBuilder();
        sb.append("当原始微信报单存在省略、歧义或错误时，若人工修正文案提供了更完整的号码、玩法或注数，应优先按修正文案解析。");
        if (correctedText != null) {
            sb.append("修正文案示例: ").append(trimForPrompt(correctedText)).append("。");
        }
        if (targetNumber != null && correctBet != null && correctBet > 0) {
            sb.append("其中号码 ").append(targetNumber).append(" 的正确注数按 ").append(correctBet).append(" 处理。");
        }
        if (resultSummary != null) {
            sb.append("正确结果参考: ").append(trimForPrompt(resultSummary)).append("。");
        }
        return sb.toString();
    }

    private String formatSuccessItem(AiParseResult item) {
        AiParseResult.ParsedData data = item.getData();
        if (data == null) {
            return "";
        }
        String numbers = data.getNumbers() == null ? "[]" : data.getNumbers().toString();
        return String.format("%s/%s/%s/%s numbers=%s bet=%s amount=%s",
                nullSafe(data.getCategory()),
                nullSafe(data.getGame()),
                nullSafe(data.getPlay()),
                nullSafe(data.getZone()),
                numbers,
                data.getBet(),
                data.getAmount());
    }

    private String trimForPrompt(String value) {
        String normalized = normalize(value);
        if (normalized == null) {
            return "-";
        }
        if (normalized.length() <= maxTextLength) {
            return normalized;
        }
        return normalized.substring(0, maxTextLength) + "...";
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().replace("\r", " ").replace("\n", " ");
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String nullSafe(String value) {
        return value == null ? "-" : value;
    }

    @Data
    public static class PromptCase {
        private String createdAt;
        private Long rawId;
        private String lotteryCategory;
        private String gameType;
        private String originalText;
        private String correctedText;
        private String targetNumber;
        private Integer correctBet;
        private String resultSummary;
        private String patchSummary;
    }

    private record ScoredPromptCase(PromptCase promptCase, int score) {
    }
}
