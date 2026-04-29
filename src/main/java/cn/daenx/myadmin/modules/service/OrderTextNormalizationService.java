package cn.daenx.myadmin.modules.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OrderTextNormalizationService {

    private static final Pattern SINGLE_BET_TYPO =
            Pattern.compile("(?<![A-Za-z])((?:各)?[零一二三四五六七八九十百两\\d]+)丹(?![A-Za-z])");
    private static final Pattern GROUP_COUNT_TYPO =
            Pattern.compile("(?<![A-Za-z])((?:各)?[零一二三四五六七八九十百两\\d]+)租(?![A-Za-z])");
    private static final Pattern KEYCAP_DIGIT =
            Pattern.compile("([0-9])\\uFE0F?\\u20E3");
    private static final Pattern KEYCAP_ADJACENT_DIRECT_BET =
            Pattern.compile("(?<!\\d)(\\d{3})(\\d{1,3})单(?![A-Za-z])");

    private static final List<SimpleAlias> SIMPLE_ALIASES = List.of(
            new SimpleAlias("复试", "复式"),
            new SimpleAlias("复是", "复式"),
            new SimpleAlias("付式", "复式"),
            new SimpleAlias("付试", "复式"),
            new SimpleAlias("负式", "复式"),
            new SimpleAlias("负试", "复式"),
            new SimpleAlias("值选", "直选"),
            new SimpleAlias("知选", "直选"),
            new SimpleAlias("之选", "直选"),
            new SimpleAlias("副停", "福停"),
            new SimpleAlias("付停", "福停")
    );

    public String normalizeForParsing(String text) {
        return apply(text).text();
    }

    public String buildAiHint(String text) {
        NormalizationResult result = apply(text);
        if (result.changes().isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[常见错字归一化提示]\n");
        sb.append("原始消息里存在可能的同音或近形误写。解析时请先按以下语义修正后再理解：\n");
        for (String change : result.changes()) {
            sb.append("- ").append(change).append("\n");
        }
        return sb.toString().trim();
    }

    public boolean containsNormalizedKeyword(String text, String keyword) {
        if (text == null || keyword == null || keyword.isBlank()) {
            return false;
        }
        return normalizeForParsing(text).contains(normalizeForParsing(keyword));
    }

    private NormalizationResult apply(String text) {
        if (text == null || text.isBlank()) {
            return new NormalizationResult(text == null ? "" : text, List.of());
        }

        String normalized = text;
        Set<String> changes = new LinkedHashSet<>();

        for (SimpleAlias alias : SIMPLE_ALIASES) {
            if (normalized.contains(alias.from())) {
                normalized = normalized.replace(alias.from(), alias.to());
                changes.add(alias.from() + " -> " + alias.to());
            }
        }

        normalized = replaceByPattern(normalized, SINGLE_BET_TYPO, "单", changes);
        normalized = replaceByPattern(normalized, GROUP_COUNT_TYPO, "组", changes);
        normalized = normalizeKeycapDigits(normalized, changes);

        return new NormalizationResult(normalized, new ArrayList<>(changes));
    }

    private String replaceByPattern(String text,
                                    Pattern pattern,
                                    String suffix,
                                    Set<String> changes) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String source = matcher.group();
            String replacement = matcher.group(1) + suffix;
            if (!source.equals(replacement)) {
                changes.add(source + " -> " + replacement);
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        if (!found) {
            return text;
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String normalizeKeycapDigits(String text, Set<String> changes) {
        Matcher matcher = KEYCAP_DIGIT.matcher(text);
        StringBuffer sb = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group(1)));
        }
        if (!found) {
            return text;
        }
        matcher.appendTail(sb);
        changes.add("表情数字 -> 普通数字");

        String normalized = sb.toString();
        Matcher adjacentBet = KEYCAP_ADJACENT_DIRECT_BET.matcher(normalized);
        StringBuffer splitSb = new StringBuffer();
        boolean split = false;
        while (adjacentBet.find()) {
            split = true;
            String source = adjacentBet.group();
            String replacement = adjacentBet.group(1) + " " + adjacentBet.group(2) + "单";
            changes.add(source + " -> " + replacement);
            adjacentBet.appendReplacement(splitSb, Matcher.quoteReplacement(replacement));
        }
        if (!split) {
            return normalized;
        }
        adjacentBet.appendTail(splitSb);
        return splitSb.toString();
    }

    private record SimpleAlias(String from, String to) {
    }

    private record NormalizationResult(String text, List<String> changes) {
    }
}
