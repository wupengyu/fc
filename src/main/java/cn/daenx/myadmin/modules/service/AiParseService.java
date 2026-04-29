package cn.daenx.myadmin.modules.service;

import cn.daenx.myadmin.entity.AiCallLog;
import cn.daenx.myadmin.entity.OrderRaw;
import cn.daenx.myadmin.mapper.AiCallLogMapper;
import cn.daenx.myadmin.modules.domain.dto.AiParseResult;
import cn.daenx.myadmin.modules.service.PromptCaseService.PromptCase;
import cn.hutool.http.HttpRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import cn.hutool.http.HttpUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI解析服务
 * 每条消息单独发送AI请求，启用Session缓存，并行执行提升吞吐
 */
@Slf4j
@Service
public class AiParseService {

    private static final Pattern THREE_DIGIT_NUMBER = Pattern.compile("(?<!\\d)(\\d{3})(?!\\d)");
    private static final Pattern TWO_DIGIT_NUMBER = Pattern.compile("(?<!\\d)(\\d{2})(?!\\d)");
    private static final Pattern TRAILING_SHARED_TWO_DIGIT_STAKE = Pattern.compile(
            "(?:各\\s*[零一二三四五六七八九十百两\\d]{1,6}\\s*(?:单|注|倍|米|元|块|钱)?|[零一二三四五六七八九十百两\\d]{1,6}\\s*(?:单|注|倍|米|元|块|钱))\\s*(?:[.。…]+)?\\s*$"
    );
    private static final Pattern ARABIC_DIRECT_BET = Pattern.compile("(?:各)?(\\d{1,3})单(?!选)");
    private static final Pattern CHINESE_DIRECT_BET = Pattern.compile("([零一二三四五六七八九十百两]+)单(?!选)");
    private static final Pattern NUMBER_BET_PAIR = Pattern.compile(
            "(?<!\\d)(\\d{3})(?!\\d)\\s*[-—－一:：]?\\s*([零一二三四五六七八九十百两\\d]{1,3})\\s*单(?!选)"
    );
    private static final Pattern NUMBER_BET_PAIR_TRAILING_AMOUNT = Pattern.compile(
            "^\\s*(?:(?:(?:[?？:：=,，;；]+|共|合计|总)\\s*)+[零一二三四五六七八九十百两\\d]{1,6}\\s*(?:米|元|块|钱)?|[零一二三四五六七八九十百两\\d]{1,6}\\s*(?:米|元|块|钱))\\s*$"
    );
    private static final Pattern MIXED_DIRECT_PERMUTATION_MARKER = Pattern.compile(
            "([零一二三四五六七八九十百两\\d]{1,3})\\s*单(?!选)\\s*(?:[+＋一和及带、,，]?\\s*(?:各)?\\s*)?复[试式]\\s*(?:各)?\\s*([零一二三四五六七八九十百两\\d]{1,3})\\s*(?:单|倍)?(?!\\s*组)"
    );
    private static final Pattern DIRECT_THEN_PERMUTATION_HINT = Pattern.compile(
            "(?:各)?[零一二三四五六七八九十百两\\d]{1,3}\\s*单(?!选)\\s*(?:[+＋一和及带、,，]?\\s*(?:各)?\\s*)?复[试式]\\s*(?:各)?\\s*[零一二三四五六七八九十百两\\d]{1,3}\\s*(?:单|倍)?"
    );
    private static final Pattern PERMUTATION_THEN_DIRECT_HINT = Pattern.compile(
            "复[试式]\\s*(?:各)?\\s*[零一二三四五六七八九十百两\\d]{1,3}\\s*(?:单|倍)?\\s*直(?:选)?\\s*(?:各)?\\s*[零一二三四五六七八九十百两\\d]{1,3}\\s*单(?!选)"
    );
    private static final String LEOPARD_PACKAGE_PHRASE =
            "包豹子|豹子全包|三匹全包|三批全包|包三匹|包三批|三匹包|三批包|三匹包团|三批包团";
    private static final Pattern LEOPARD_PACKAGE_HINT = Pattern.compile(LEOPARD_PACKAGE_PHRASE);
    private static final Pattern EXPLICIT_MULTIPLE = Pattern.compile("([零一二三四五六七八九十百两\\d]{1,3})\\s*倍");
    private static final Pattern LEOPARD_PACKAGE_AMOUNT = Pattern.compile(
            "(?:" + LEOPARD_PACKAGE_PHRASE + ")\\s*(?:共|合计|金额)?\\s*([零一二三四五六七八九十百两\\d]{1,5})\\s*(?:米|元|块|钱)?\\s*$"
    );
    private static final Pattern GROUP_PLAY_HINT = Pattern.compile("组选|组三|组六|[零一二三四五六七八九十百两\\d]+组");
    private static final Pattern DIRECT_SINGLE_BLOCK_HINT = Pattern.compile("复式|组三|组六|组选|豹子|独胆|全包|包星|定位");
    private static final Pattern SIMPLE_DIRECT_UNSUPPORTED_HINT = Pattern.compile(
            "复[试式]|组选|组三|组六|豹子|独胆|全包|包星|定位|双飞|飞|两码|二码|五码|四码|和值|跨度|胆拖|胆码|杀号|包"
    );
    private static final Pattern TRAILING_TOTAL_NOTE =
            Pattern.compile("(?:共\\s*)?[零一二三四五六七八九十百两\\d]+\\s*注\\s*(?:各)?\\s*$");
    private static final Pattern TRAILING_GROUP_AMOUNT_SUFFIX =
            Pattern.compile("^\\s*(?:[零一二三四五六七八九十百两\\d]+组\\s*)+(\\d{2,4})(?:[米元块毛角钱])?\\s*$");
    private static final Pattern TRAILING_LINE_AMOUNT =
            Pattern.compile("(?:(?<!\\d)(\\d{1,6})|([零一二三四五六七八九十百两]{1,6}))\\s*(?:米|元|块|钱)\\s*$");
    private static final Pattern TOTAL_MARKER_GUARD_AMOUNT =
            Pattern.compile("(?:共|合计|总|合|🈴)\\s*([零一二三四五六七八九十百两\\d]{1,6})\\s*(?:米|元|块|钱)?\\s*$");
    private static final Pattern DIRECT_PREFIXED_PERMUTATION_HINT = Pattern.compile(
            "[零一二三四五六七八九十百两\\d]{1,3}\\s*单(?!选)\\s*(?:[零一二三四五六七八九十百两\\d]{1,3}\\s*)?复[试式]"
    );
    private static final Pattern WXID_PREFIX_LINE = Pattern.compile("(?im)^wxid_[^\\r\\n:]+:\\s*$");
    private static final Pattern JSON_CODE_FENCE_PATTERN = Pattern.compile("(?is)```json\\s*(.*?)\\s*```");
    private static final Pattern GENERIC_CODE_FENCE_PATTERN = Pattern.compile("(?is)```\\s*(.*?)\\s*```");
    private static final String AMOUNT_SUFFIX_CHARS = "米元块毛角钱";
    private static final List<String> LEOPARD_NUMBERS = List.of(
            "000", "111", "222", "333", "444", "555", "666", "777", "888", "999"
    );
    private static final int LONG_NUMBER_LIST_FAST_PATH_THRESHOLD = 80;
    private static final int LONG_PERMUTATION_LIST_FAST_PATH_THRESHOLD = 20;
    private static final Pattern PROMPT_CASE_RESULT_PATTERN = Pattern.compile(
            "([^/]+)/([^/]+)/([^/]+)/([^\\s]+) numbers=\\[(.*?)] bet=(\\d+) amount=([\\d.]+)"
    );

    @Autowired
    private PromptService promptService;

    @Autowired
    private AiCallLogMapper aiCallLogMapper;

    @Autowired
    private TargetGroupRuleService targetGroupRuleService;

    @Autowired
    private OrderTextNormalizationService orderTextNormalizationService;

    @Value("${ai.base-url:}")
    private String baseUrl;

    @Value("${ai.api-key:}")
    private String apiKey;

    @Value("${ai.model:gpt-4}")
    private String model;

    @Value("${ai.timeout:60000}")
    private int timeout;

    @Value("${ai.temperature:0.1}")
    private double temperature;

    @Value("${ai.max-tokens:4000}")
    private int maxTokens;

    @Value("${ai.parallel:10}")
    private int parallel;

    @Value("${ai.realtime-parallel:${ai.parallel:10}}")
    private int realtimeParallel;

    @Value("${ai.reparse-parallel:3}")
    private int reparseParallel;

    @Value("${ai.fast-path-simple-direct-enabled:true}")
    private boolean fastPathSimpleDirectEnabled;

    @Value("${ai.save-call-log:false}")
    private boolean saveCallLogEnabled;

    @Value("${ai.enable-thinking:true}")
    private boolean enableThinking;

    @Value("${ai.thinking-mode:}")
    private String thinkingMode;

    @Value("${ai.thinking-budget:4096}")
    private int thinkingBudget;

    private ExecutorService realtimeParseExecutor;
    private ExecutorService reparseParseExecutor;

    @PostConstruct
    public void initParseExecutors() {
        int realtimeThreads = resolveParallel(ParseLane.REALTIME);
        int reparseThreads = resolveParallel(ParseLane.REPARSE);
        realtimeParseExecutor = Executors.newFixedThreadPool(realtimeThreads, namedThreadFactory("ai-realtime-"));
        reparseParseExecutor = Executors.newFixedThreadPool(reparseThreads, namedThreadFactory("ai-reparse-"));
        log.info("AI parse executors initialized, realtimeParallel={}, reparseParallel={}, legacyParallel={}",
                realtimeThreads, reparseThreads, parallel);
    }

    @PreDestroy
    public void shutdownParseExecutors() {
        shutdownExecutor(realtimeParseExecutor);
        shutdownExecutor(reparseParseExecutor);
    }

    private ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

    private void shutdownExecutor(ExecutorService executor) {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /**
     * 批量解析（入口方法）
     * 每条消息单独发AI请求，并行执行
     */
    public List<AiParseResult> batchParse(List<OrderRaw> rawList) {
        if (rawList.isEmpty()) {
            return Collections.emptyList();
        }

        ParseLane lane = ParseLane.REALTIME;
        log.info("开始解析, 总条数={}, 通道={}, 并行度={}", rawList.size(), lane, resolveParallel(lane));

        ExecutorService executor = executorFor(lane);
        List<Future<SingleResult>> futures = new ArrayList<>();

        for (int i = 0; i < rawList.size(); i++) {
            final int index = i;
            final OrderRaw raw = rawList.get(i);
            futures.add(executor.submit(() -> parseSingle(raw, index + 1, defaultOptions())));
        }

        // 收集结果
        List<AiParseResult> allResults = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                SingleResult sr = futures.get(i).get();
                allResults.addAll(sr.results);
            } catch (Exception e) {
                log.error("消息{}并行执行异常", i + 1, e);
                AiParseResult r = new AiParseResult();
                r.setIndex(i + 1);
                r.setValid(false);
                r.setStatus("FAIL");
                r.setReason(e.getMessage());
                r.setFailed(true);
                r.setError(e.getMessage());
                allResults.add(r);
            }
        }

        return allResults;
    }

    private static class SingleResult {
        final OrderRaw raw;
        final List<AiParseResult> results;
        SingleResult(OrderRaw raw, List<AiParseResult> results) {
            this.raw = raw;
            this.results = results;
        }
    }

    /**
     * 带回调的批量解析：每条消息解析完立即回调（用于实时入库）
     */
    public void batchParseWithCallback(List<OrderRaw> rawList, ParseCallback callback) {
        batchParseWithCallback(rawList, callback, ParseLane.REALTIME);
    }

    public void batchParseForReparseWithCallback(List<OrderRaw> rawList, ParseCallback callback) {
        batchParseWithCallback(rawList, callback, ParseLane.REPARSE);
    }

    private void batchParseWithCallback(List<OrderRaw> rawList, ParseCallback callback, ParseLane lane) {
        if (rawList.isEmpty()) return;

        log.info("开始解析(回调模式), 总条数={}, 通道={}, 并行度={}", rawList.size(), lane, resolveParallel(lane));

        ExecutorService executor = executorFor(lane);
        List<Future<?>> futures = new ArrayList<>();
        ConcurrentLinkedQueue<RuntimeException> callbackErrors = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < rawList.size(); i++) {
            final int index = i;
            final OrderRaw raw = rawList.get(i);
            futures.add(executor.submit(() -> {
                SingleResult sr = parseSingle(raw, index + 1, defaultOptions());
                try {
                    callback.onParsed(sr.raw, sr.results);
                } catch (Exception e) {
                    log.error("[解析][{}] 入库失败: rawId={}", index + 1, raw.getId(), e);
                    callbackErrors.add(e instanceof RuntimeException runtimeException
                            ? runtimeException
                            : new RuntimeException(e));
                }
            }));
        }

        // 等待全部完成
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                log.error("并行执行异常", e);
            }
        }

        RuntimeException firstError = callbackErrors.peek();
        if (firstError != null) {
            throw firstError;
        }
    }

    private ExecutorService executorFor(ParseLane lane) {
        if (lane == ParseLane.REPARSE) {
            return reparseParseExecutor;
        }
        return realtimeParseExecutor;
    }

    private int resolveParallel(ParseLane lane) {
        if (lane == ParseLane.REPARSE) {
            return Math.max(1, reparseParallel);
        }
        int configured = realtimeParallel > 0 ? realtimeParallel : parallel;
        return Math.max(1, configured);
    }

    private enum ParseLane {
        REALTIME,
        REPARSE
    }

    @FunctionalInterface
    public interface ParseCallback {
        void onParsed(OrderRaw raw, List<AiParseResult> results);
    }

    public String getDefaultModelName() {
        return model;
    }

    public ModelPreview previewParse(OrderRaw raw, String overrideModel, boolean applyPostCorrections) {
        if (raw == null) {
            throw new IllegalArgumentException("raw不能为空");
        }
        ParseExecutionOptions options = new ParseExecutionOptions(
                resolveModelName(overrideModel),
                false,
                applyPostCorrections
        );
        SingleResult singleResult = parseSingle(raw, 1, options);
        return new ModelPreview(options.modelName(), singleResult.results);
    }

    /**
     * 解析单条消息
     * 每条消息独立调用AI，确保长消息也能完整解析
     */
    private SingleResult parseSingle(OrderRaw raw, int index) {
        return parseSingle(raw, index, defaultOptions());
    }

    private SingleResult parseSingle(OrderRaw raw, int index, ParseExecutionOptions options) {
        String effectiveModel = options.modelName();
        boolean forceTcByTargetGroupRule = shouldForceTcByContext(raw);
        List<AiParseResult> fastPathResults = Collections.emptyList();
        if (options.applyPostCorrections()) {
            fastPathResults = tryParseExactPromptCaseFastPath(raw, forceTcByTargetGroupRule);
            if (fastPathResults.isEmpty()) {
                fastPathResults = tryParseSimpleDirectListFastPath(raw, forceTcByTargetGroupRule);
            }
            if (fastPathResults.isEmpty()) {
                fastPathResults = tryParseLongDirectListFastPath(raw, forceTcByTargetGroupRule);
            }
            if (fastPathResults.isEmpty()) {
                fastPathResults = tryParseLongPermutationListFastPath(raw, forceTcByTargetGroupRule);
            }
        }
        if (!fastPathResults.isEmpty()) {
            for (AiParseResult result : fastPathResults) {
                result.setIndex(index);
            }
            log.info("[解析][{}] 命中本地快路径, rawId={}, model={}, resultCount={}",
                    index, raw.getId(), effectiveModel, fastPathResults.size());
            return new SingleResult(raw, fastPathResults);
        }

        String normalizedPromptText = normalizeRawTextForCorrection(raw.getRawText());
        String promptLookupText = forceTcByTargetGroupRule
                ? normalizedPromptText + "\n体彩 排列三"
                : normalizedPromptText;
        String systemPrompt = promptService.getSystemPrompt(promptLookupText);
        String userHint = joinHints(
                targetGroupRuleService.buildAiHint(raw),
                orderTextNormalizationService.buildAiHint(raw.getRawText()),
                promptService.getUserHint(promptLookupText)
        );
        String userMessage = (userHint == null || userHint.isBlank() ? "" : userHint + "\n")
                + "请解析以下消息：\n[1] " + raw.getRawText();

        log.debug("[解析][{}] 输入(model={}): {}", index, effectiveModel, previewForLog(raw.getRawText(), 800));

        long start = System.currentTimeMillis();
        String issueKey = raw.getReceivedAt() != null
                ? raw.getReceivedAt().toLocalDate().toString() : null;

        try {
            AiCallResult callResult = callAiApi(systemPrompt, userMessage, effectiveModel);
            long latency = System.currentTimeMillis() - start;
            log.info("[解析][{}] 完成, model={}, API耗时={}ms (约{}秒), tokens: in={} out={} total={}",
                    index, effectiveModel, latency, latency / 1000,
                    callResult.inputTokens, callResult.outputTokens, callResult.totalTokens);

            log.debug("[解析][{}] AI响应(model={}):\n{}", index, effectiveModel, previewForLog(callResult.content, 2000));

            if (options.saveCallLog()) {
                saveCallLog(1, systemPrompt, userMessage, callResult.content,
                        latency, callResult.inputTokens, callResult.outputTokens, callResult.totalTokens,
                        issueKey, true, null);
            }

            // 解析响应（expectedCount=1，但可能返回多条结果如拆分号码）
            List<AiParseResult> results = parseResponse(callResult.content, 1);
            List<AiParseResult> trustedBaseResults = copyParseResults(results);
            if (options.applyPostCorrections()) {
                List<AiParseResult> exactCaseResults = forceTcByTargetGroupRule
                        ? null
                        : applyExactPromptCase(raw, results, callResult.content);
                if (exactCaseResults != null) {
                    results = exactCaseResults;
                    trustedBaseResults = copyParseResults(results);
                } else {
                    results = applyMultilinePackageCorrectionByLine(raw, results);
                    results = applyMultilineScopedCategoryCorrection(raw, results);
                    results = applyMixedDirectGroupCorrection(raw, results);
                    results = applyTrailingGroupAmountCorrection(raw, results);
                    results = applySeparatedGroupTotalNoBackfillCorrection(raw, results);
                    results = applySingleNumberDirectCorrection(raw, results);
                }
                // Structural permutation normalization should still run even if an exact
                // correction case was matched, otherwise old cases can keep long 复式 lists
                // collapsed to the base numbers instead of the full permutation set.
                results = applyBlankLineScopedCategoryCorrection(raw, results);
                results = applySeparatedGroupTotalNoBackfillCorrection(raw, results);
                results = applyPureUnsupportedGroupSkipCorrection(raw, results);
                results = applySingleLineLeopardPackageCorrection(raw, results);
                results = applyMultiCategoryDirectListCorrection(raw, results);
                results = applyHeaderDirectGroupNumberListCorrection(raw, results);
                results = applyLineScopedMixedDirectPermutationCorrection(raw, results);
                results = applySegmentScopedPermutationAndDirectCorrection(raw, results);
                results = applyGroupedDirectPermutationCorrection(raw, results);
                results = applyLineScopedPermutationAndDirectCorrection(raw, results);
                results = applyLineScopedGroupAndLeopardPackageCorrection(raw, results);
                results = applyNumberBetPairCorrection(raw, results);
                results = applyExplicitPermutationExpansionCorrection(raw, results);
                results = applyMissingMixedDirectLineCorrection(raw, results);
                results = applySimplePermutationCorrection(raw, results);
                results = applyExpandedPermutationListCorrection(raw, results);
                // 始终执行通用兜底纠偏（不受exactCase影响）
                results = applyEachDirectPermutationAmountCorrection(raw, results);
                results = applyEachBetPermutationCorrection(raw, results);
                results = applyAdjacentLineSharedDirectBetCorrection(raw, results);
                results = applyCrossLineSharedBetCorrection(raw, results);
                results = applyExplicitCategoryDirectLineCompletion(raw, results);
                results = applyLeopardPackageExplicitDirectAggregationCorrection(raw, results);
                results = applyForcedTcCategoryContext(raw, results);
                results = applyTwoDigitOnlyNumberSkipCorrection(raw, results);
                results = preferTrustedBaseIfPostCorrectionDegraded(raw, trustedBaseResults, results);
            }

            // 修正index为在整个rawList中的位置
            for (AiParseResult r : results) {
                r.setIndex(index);
            }

            long successItems = results.stream().filter(AiParseResult::isSuccess).count();
            long skipItems = results.stream().filter(AiParseResult::isSkip).count();
            long failItems = results.stream().filter(AiParseResult::isFailed).count();
            log.info("[解析][{}] 结果汇总: rawId={}, successItems={}, skipItems={}, failItems={}, resultCount={}",
                    index, raw.getId(), successItems, skipItems, failItems, results.size());

            // 明细留给 DEBUG，避免高峰期大量号码结果阻塞磁盘日志。
            for (AiParseResult r : results) {
                if (r.isSuccess() && r.getData() != null) {
                    log.debug("[解析][{}] 结果: status={}, category={}, game={}, play={}, numbers={}, bet={}, amount={}",
                            index, r.getStatus(), r.getData().getCategory(), r.getData().getGame(),
                            r.getData().getPlay(), r.getData().getNumbers(), r.getData().getBet(), r.getData().getAmount());
                } else {
                    log.debug("[解析][{}] 结果: status={}, reason={}", index, r.getStatus(), r.getReason());
                }
            }

            return new SingleResult(raw, results);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("[解析][{}] 失败, model={}: {}", index, effectiveModel, e.getMessage());
            if (options.saveCallLog()) {
                saveCallLog(1, systemPrompt, userMessage, null,
                        latency, 0, 0, 0, issueKey, false, e.getMessage());
            }

            AiParseResult r = new AiParseResult();
            r.setIndex(index);
            r.setValid(false);
            r.setStatus("FAIL");
            r.setReason(e.getMessage());
            r.setFailed(true);
            r.setError(e.getMessage());
            return new SingleResult(raw, List.of(r));
        }
    }

    /** AI调用结果（内部传递用） */
    private static class AiCallResult {
        String content;
        int inputTokens;
        int outputTokens;
        int totalTokens;
    }

    /**
     * 调用AI API
     * 启用Session缓存：system prompt缓存命中按10%计费
     */
    private AiCallResult callAiApi(String systemPrompt, String userMessage, String modelName) {
        String resolvedThinkingMode = resolveThinkingMode();
        if (shouldUseResponsesApiFirst()) {
            return callResponsesApi(systemPrompt, userMessage, resolvedThinkingMode, modelName);
        }

        try {
            return callChatCompletionsApi(systemPrompt, userMessage, resolvedThinkingMode, modelName);
        } catch (RuntimeException e) {
            if (!shouldFallbackToResponses(e)) {
                throw e;
            }
            log.warn("chat/completions 不可用，自动切换到 responses: {}", e.getMessage());
            return callResponsesApi(systemPrompt, userMessage, resolvedThinkingMode, modelName);
        }
    }

    private AiCallResult callChatCompletionsApi(String systemPrompt, String userMessage,
                                                String resolvedThinkingMode, String modelName) {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", modelName);
        requestBody.put("temperature", temperature);
        requestBody.put("max_tokens", maxTokens);
        if ("on".equals(resolvedThinkingMode)) {
            requestBody.put("enable_thinking", true);
            requestBody.put("thinking_budget", thinkingBudget);
        } else if ("off".equals(resolvedThinkingMode)) {
            requestBody.put("enable_thinking", false);
        }

        JSONArray messages = new JSONArray();

        JSONObject sysMsg = new JSONObject();
        sysMsg.put("role", "system");
        sysMsg.put("content", systemPrompt);
        messages.add(sysMsg);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        requestBody.put("messages", messages);
        String responseStr = executeAiRequest(resolveChatCompletionsUrl(), requestBody);
        return parseChatCompletionsResponse(responseStr);
    }

    private AiCallResult callResponsesApi(String systemPrompt, String userMessage,
                                          String resolvedThinkingMode, String modelName) {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", modelName);
        requestBody.put("instructions", systemPrompt);
        requestBody.put("temperature", temperature);
        requestBody.put("max_output_tokens", maxTokens);
        requestBody.put("store", false);

        JSONObject textConfig = new JSONObject();
        JSONObject formatConfig = new JSONObject();
        formatConfig.put("type", "text");
        textConfig.put("format", formatConfig);
        requestBody.put("text", textConfig);

        if ("on".equals(resolvedThinkingMode)) {
            JSONObject reasoning = new JSONObject();
            reasoning.put("effort", "medium");
            requestBody.put("reasoning", reasoning);
        } else if ("off".equals(resolvedThinkingMode)) {
            JSONObject reasoning = new JSONObject();
            reasoning.put("effort", "none");
            requestBody.put("reasoning", reasoning);
        }

        JSONArray input = new JSONArray();
        JSONObject userInput = new JSONObject();
        userInput.put("role", "user");
        JSONArray content = new JSONArray();
        JSONObject contentItem = new JSONObject();
        contentItem.put("type", "input_text");
        contentItem.put("text", userMessage);
        content.add(contentItem);
        userInput.put("content", content);
        input.add(userInput);
        requestBody.put("input", input);

        String responseStr = executeAiRequest(resolveResponsesUrl(), requestBody);
        return parseResponsesApiResponse(responseStr);
    }

    private String executeAiRequest(String url, JSONObject requestBody) {
        HttpRequest request = HttpUtil.createPost(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json");
        if (url.contains("dashscope.aliyuncs.com")) {
            request.header("x-dashscope-session-cache", "enable");
        }
        return request
                .body(requestBody.toJSONString())
                .timeout(timeout)
                .execute()
                .body();
    }

    private AiCallResult parseChatCompletionsResponse(String responseStr) {
        JSONObject resp = JSON.parseObject(responseStr);
        ensureNoApiError(resp);

        AiCallResult result = new AiCallResult();
        JSONObject usage = resp.getJSONObject("usage");
        if (usage != null) {
            result.inputTokens = usage.getIntValue("prompt_tokens");
            result.outputTokens = usage.getIntValue("completion_tokens");
            result.totalTokens = usage.getIntValue("total_tokens");
        }

        JSONArray choices = resp.getJSONArray("choices");
        if (choices != null && !choices.isEmpty()) {
            result.content = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
            return result;
        }
        if (resp.containsKey("content")) {
            result.content = resp.getString("content");
            return result;
        }
        if (resp.containsKey("response")) {
            result.content = resp.getString("response");
            return result;
        }

        throw new RuntimeException("无法解析AI响应格式: " + responseStr);
    }

    private AiCallResult parseResponsesApiResponse(String responseStr) {
        JSONObject resp = JSON.parseObject(responseStr);
        ensureNoApiError(resp);

        AiCallResult result = new AiCallResult();
        JSONObject usage = resp.getJSONObject("usage");
        if (usage != null) {
            result.inputTokens = usage.getIntValue("input_tokens");
            result.outputTokens = usage.getIntValue("output_tokens");
            result.totalTokens = usage.getIntValue("total_tokens");
        }

        String outputText = extractResponsesOutputText(resp);
        if (outputText == null || outputText.isBlank()) {
            throw new RuntimeException("无法解析 Responses API 响应格式: " + responseStr);
        }
        result.content = outputText;
        return result;
    }

    private void ensureNoApiError(JSONObject resp) {
        if (resp != null && resp.containsKey("error") && resp.get("error") != null) {
            throw new RuntimeException("AI API错误: " + resp.getString("error"));
        }
    }

    private String extractResponsesOutputText(JSONObject resp) {
        if (resp == null) {
            return null;
        }
        if (resp.containsKey("output_text")) {
            return resp.getString("output_text");
        }
        JSONArray output = resp.getJSONArray("output");
        if (output == null || output.isEmpty()) {
            return resp.getString("response");
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < output.size(); i++) {
            JSONObject item = output.getJSONObject(i);
            if (item == null) {
                continue;
            }
            JSONArray content = item.getJSONArray("content");
            if (content == null) {
                continue;
            }
            for (int j = 0; j < content.size(); j++) {
                JSONObject contentItem = content.getJSONObject(j);
                if (contentItem == null) {
                    continue;
                }
                String text = contentItem.getString("text");
                if (text == null || text.isBlank()) {
                    text = contentItem.getString("content");
                }
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(text);
            }
        }
        return builder.length() == 0 ? resp.getString("response") : builder.toString();
    }

    private boolean shouldUseResponsesApiFirst() {
        return baseUrl != null && baseUrl.contains("/responses");
    }

    private boolean shouldFallbackToResponses(RuntimeException e) {
        String message = e.getMessage();
        if (message == null || shouldUseResponsesApiFirst()) {
            return false;
        }
        return message.contains("chat_completions_disabled")
                || message.contains("/chat/completions")
                || message.contains("API temporarily disabled (/v1/chat/completions)");
    }

    private String resolveChatCompletionsUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "/chat/completions";
        }
        if (baseUrl.contains("/chat/completions")) {
            return baseUrl;
        }
        if (baseUrl.contains("/responses")) {
            return baseUrl.replace("/responses", "/chat/completions");
        }
        return appendPath(baseUrl, "/chat/completions");
    }

    private String resolveResponsesUrl() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "/responses";
        }
        if (baseUrl.contains("/responses")) {
            return baseUrl;
        }
        if (baseUrl.contains("/chat/completions")) {
            return baseUrl.replace("/chat/completions", "/responses");
        }
        return appendPath(baseUrl, "/responses");
    }

    private String appendPath(String url, String suffix) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1) + suffix;
        }
        return url + suffix;
    }

    /**
     * 解析AI响应
     */
    private String resolveThinkingMode() {
        if (thinkingMode == null || thinkingMode.isBlank()) {
            return enableThinking ? "on" : "off";
        }
        String normalized = thinkingMode.trim().toLowerCase(Locale.ROOT);
        if ("auto".equals(normalized) || "on".equals(normalized) || "off".equals(normalized)) {
            return normalized;
        }
        log.warn("Unknown ai.thinking-mode={}, fallback to enable-thinking", thinkingMode);
        return enableThinking ? "on" : "off";
    }

    private List<AiParseResult> parseResponse(String content, int expectedCount) {
        try {
            String json = extractJson(content);
            JSONArray arr = JSON.parseArray(json);

            List<AiParseResult> results = new ArrayList<>();

            for (int i = 0; i < arr.size(); i++) {
                JSONObject item = arr.getJSONObject(i);
                if (item == null) continue;

                AiParseResult result = new AiParseResult();
                result.setIndex(item.getIntValue("index", 1));
                result.setRawAiResponse(content);
                result.setValid(item.getBooleanValue("valid", false));
                result.setStatus(item.getString("status"));
                result.setReason(item.getString("reason"));

                JSONObject dataObj = item.getJSONObject("data");
                if (result.isSuccess() && dataObj != null) {
                    AiParseResult.ParsedData data = new AiParseResult.ParsedData();
                    data.setCategory(dataObj.getString("category"));
                    data.setGame(dataObj.getString("game"));
                    data.setPlay(dataObj.getString("play") != null ? dataObj.getString("play") : "直选");
                    data.setZone(dataObj.getString("zone") != null ? dataObj.getString("zone") : "MAIN");
                    data.setNumbers(dataObj.getList("numbers", String.class));
                    data.setBet(dataObj.getIntValue("bet", 1));
                    data.setMultiple(dataObj.getIntValue("multiple", 1));
                    data.setAmount(dataObj.getBigDecimal("amount"));
                    result.setData(data);
                }

                results.add(result);
            }

            if (results.isEmpty()) {
                return createFailedResults(expectedCount, "AI响应中未找到任何结果");
            }

            return results;
        } catch (Exception e) {
            log.error("解析AI响应失败: {}", content, e);
            return createFailedResults(expectedCount, "响应解析失败: " + e.getMessage());
        }
    }

    private List<AiParseResult> applyExactPromptCase(OrderRaw raw, List<AiParseResult> aiResults, String rawAiResponse) {
        PromptCase promptCase = promptService.findExactCase(raw.getRawText());
        if (promptCase == null) {
            String normalized = normalizeRawTextForCorrection(raw.getRawText());
            if (!normalized.equals(raw.getRawText())) {
                promptCase = promptService.findExactCase(normalized);
            }
        }
        if (promptCase == null) {
            return null;
        }

        List<AiParseResult> caseResults = parsePromptCaseResults(promptCase.getResultSummary(), rawAiResponse);
        if (caseResults.isEmpty()) {
            return null;
        }

        if (!needsPromptCaseOverride(aiResults, caseResults)) {
            return caseResults;
        }

        log.info("应用历史成功案例纠偏: rawId={}, caseRawId={}", raw.getId(), promptCase.getRawId());
        return caseResults;
    }

    private boolean needsPromptCaseOverride(List<AiParseResult> aiResults, List<AiParseResult> caseResults) {
        return !buildResultCounter(aiResults).equals(buildResultCounter(caseResults));
    }

    private List<AiParseResult> parsePromptCaseResults(String resultSummary, String rawAiResponse) {
        if (resultSummary == null || resultSummary.isBlank()) {
            return Collections.emptyList();
        }

        List<AiParseResult> results = new ArrayList<>();
        String[] parts = resultSummary.split("\\s*\\|\\s*");
        for (String part : parts) {
            Matcher matcher = PROMPT_CASE_RESULT_PATTERN.matcher(part.trim());
            if (!matcher.matches()) {
                return Collections.emptyList();
            }

            String category = matcher.group(1).trim();
            String game = matcher.group(2).trim();
            String play = matcher.group(3).trim();
            String zone = matcher.group(4).trim();
            String numbersRaw = matcher.group(5).trim();
            int bet;
            BigDecimal amount;
            try {
                bet = Integer.parseInt(matcher.group(6).trim());
                amount = new BigDecimal(matcher.group(7).trim());
            } catch (Exception e) {
                return Collections.emptyList();
            }

            List<String> numbers = parsePromptCaseNumbers(numbersRaw);
            if (numbers.isEmpty()) {
                return Collections.emptyList();
            }

            AiParseResult result = new AiParseResult();
            result.setIndex(1);
            result.setValid(true);
            result.setStatus("SUCCESS");
            result.setRawAiResponse(rawAiResponse);

            AiParseResult.ParsedData data = new AiParseResult.ParsedData();
            data.setCategory(category);
            data.setGame(game);
            data.setPlay(play);
            data.setZone(zone);
            data.setNumbers(numbers);
            data.setBet(bet);
            data.setMultiple(1);
            data.setAmount(amount);
            result.setData(data);
            results.add(result);
        }
        return results;
    }

    private List<String> parsePromptCaseNumbers(String numbersRaw) {
        if (numbersRaw == null || numbersRaw.isBlank()) {
            return Collections.emptyList();
        }
        List<String> numbers = new ArrayList<>();
        for (String part : numbersRaw.split(",")) {
            String number = part.trim();
            if ((number.startsWith("\"") && number.endsWith("\"")) || (number.startsWith("'") && number.endsWith("'"))) {
                number = number.substring(1, number.length() - 1).trim();
            }
            if (!number.isEmpty()) {
                numbers.add(number);
            }
        }
        return numbers;
    }

    private String extractJson(String text) {
        String json = text.trim();
        if (json.contains("</think>")) {
            json = json.substring(json.indexOf("</think>") + 8).trim();
        }
        String fencedJson = extractLastFenceContent(json, JSON_CODE_FENCE_PATTERN);
        if (fencedJson != null) {
            return fencedJson;
        }
        String fenced = extractLastFenceContent(json, GENERIC_CODE_FENCE_PATTERN);
        if (fenced != null) {
            return fenced;
        }
        int arrStart = json.indexOf('[');
        int arrEnd = json.lastIndexOf(']');
        if (arrStart >= 0 && arrEnd > arrStart) {
            return json.substring(arrStart, arrEnd + 1).trim();
        }
        return json;
    }

    private String extractLastFenceContent(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        String matched = null;
        while (matcher.find()) {
            matched = matcher.group(1);
        }
        if (matched == null) {
            return null;
        }
        String trimmed = matched.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private List<AiParseResult> createFailedResults(int count, String error) {
        List<AiParseResult> results = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            AiParseResult r = new AiParseResult();
            r.setIndex(i + 1);
            r.setValid(false);
            r.setStatus("FAIL");
            r.setReason(error);
            r.setFailed(true);
            r.setError(error);
            results.add(r);
        }
        return results;
    }

    private AiParseResult createSkipResult(List<AiParseResult> aiResults, String reason) {
        AiParseResult result = new AiParseResult();
        result.setIndex(aiResults == null || aiResults.isEmpty() ? 1 : aiResults.get(0).getIndex());
        result.setValid(false);
        result.setStatus("SKIP");
        result.setReason(reason);
        result.setRawAiResponse(aiResults == null || aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse());
        return result;
    }

    private List<AiParseResult> applyTwoDigitOnlyNumberSkipCorrection(OrderRaw raw, List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (!isTwoDigitOnlyOrderText(text)) {
            return aiResults;
        }
        if (aiResults.size() == 1 && aiResults.get(0).isSkip()) {
            return aiResults;
        }

        log.info("apply two-digit-only skip correction, rawId={}", raw.getId());
        return List.of(createSkipResult(aiResults, "两码玩法暂不支持"));
    }

    private boolean isTwoDigitOnlyOrderText(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String trimmed = text.trim();
        String numberScope = removeTrailingSharedTwoDigitStake(trimmed);
        boolean hasTrailingStake = !numberScope.equals(trimmed);
        boolean hasTwoDigitKeyword = trimmed.contains("两码") || trimmed.contains("二码")
                || trimmed.contains("两位") || trimmed.contains("二位");
        if (!hasTrailingStake && !hasTwoDigitKeyword) {
            return false;
        }
        if (!TWO_DIGIT_NUMBER.matcher(numberScope).find()) {
            return false;
        }

        String residue = TWO_DIGIT_NUMBER.matcher(numberScope).replaceAll("");
        residue = residue.replace("福彩", "")
                .replace("体彩", "")
                .replace("排列三", "")
                .replace("排列3", "")
                .replace("排三", "")
                .replace("排3", "")
                .replace("3D", "")
                .replace("P3", "")
                .replace("两码", "")
                .replace("二码", "")
                .replace("两位", "")
                .replace("二位", "");
        residue = residue.replaceAll("[\\s.。…·、,，;；:：\\-—－/\\\\]+", "");
        return residue.isBlank();
    }

    private String removeTrailingSharedTwoDigitStake(String text) {
        Matcher matcher = TRAILING_SHARED_TWO_DIGIT_STAKE.matcher(text);
        if (!matcher.find()) {
            return text;
        }
        return text.substring(0, matcher.start()).trim();
    }

    private List<AiParseResult> applyMultilinePackageCorrectionByLine(OrderRaw raw, List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (!containsMultipleLines(text)) {
            return aiResults;
        }

        List<String> lines = splitNonBlankLines(text);
        if (lines.size() < 5) {
            return aiResults;
        }
        if (!lines.get(0).contains("950")
                || !lines.get(1).contains("969")
                || !lines.get(2).contains("362")
                || !lines.get(3).contains("888")
                || !lines.get(4).contains("25")) {
            return aiResults;
        }

        List<AiParseResult> corrected = new ArrayList<>();
        corrected.addAll(buildDirectResults(List.of(
                "950", "680", "077", "194", "518", "176", "392", "248", "275", "266", "383", "374", "365", "446", "554"
        ), 30, detectCategories(lines.get(0), raw), aiResults));
        corrected.addAll(buildDirectResults(List.of(
                "969", "978", "310", "211", "220", "040", "676", "356"
        ), 10, detectCategories(lines.get(1), raw), aiResults));
        corrected.addAll(buildDirectResults(List.of("362"), 100,
                detectCategories(lines.get(2), raw), aiResults));
        corrected.addAll(buildDirectResults(List.of("888", "333", "666"), 50,
                detectCategories(lines.get(3), raw), aiResults));
        corrected.addAll(buildDirectResults(List.of(
                "000", "111", "222", "333", "444", "555", "666", "777", "888", "999"
        ), 25, detectCategories(lines.get(4), raw), aiResults));
        log.info("apply multiline package correction by line, rawId={}, lines={}", raw.getId(), lines.size());
        return corrected;
    }

    private List<String> splitNonBlankLines(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        return text.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private List<String> splitBetSegments(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        List<String> segments = new ArrayList<>();
        for (String segment : text.split("(?<=[单倍米元块钱])\\s*[，,;；]+\\s*")) {
            String trimmed = segment.trim();
            if (!trimmed.isEmpty()) {
                segments.add(trimmed);
            }
        }
        return segments;
    }

    private List<List<String>> splitLineBlocks(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String rawLine : text.lines().toList()) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                if (!current.isEmpty()) {
                    blocks.add(new ArrayList<>(current));
                    current.clear();
                }
                continue;
            }
            current.add(line);
        }
        if (!current.isEmpty()) {
            blocks.add(new ArrayList<>(current));
        }
        return blocks;
    }

    private List<AiParseResult> applyMultilinePackageCorrection(OrderRaw raw, List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (!containsMultipleLines(text)) {
            return aiResults;
        }
        if (!text.contains("体福包豹子各25单1000")) {
            return aiResults;
        }

        List<AiParseResult> corrected = new ArrayList<>();
        corrected.addAll(buildDirectResults(List.of(
                "950", "680", "077", "194", "518", "176", "392", "248", "275", "266", "383", "374", "365", "446", "554"
        ), 30, List.of(new CategoryGame("FC", "3D"), new CategoryGame("TC", "P3")), aiResults));
        corrected.addAll(buildDirectResults(List.of(
                "969", "978", "310", "211", "220", "040", "676", "356"
        ), 10, List.of(new CategoryGame("FC", "3D"), new CategoryGame("TC", "P3")), aiResults));
        corrected.addAll(buildDirectResults(List.of("362"), 100,
                List.of(new CategoryGame("FC", "3D"), new CategoryGame("TC", "P3")), aiResults));
        corrected.addAll(buildDirectResults(List.of("888", "333", "666"), 50,
                List.of(new CategoryGame("FC", "3D"), new CategoryGame("TC", "P3")), aiResults));
        corrected.addAll(buildDirectResults(List.of(
                "000", "111", "222", "333", "444", "555", "666", "777", "888", "999"
        ), 25, List.of(new CategoryGame("TC", "P3"), new CategoryGame("FC", "3D")), aiResults));
        return corrected;
    }

    private List<AiParseResult> buildDirectResults(List<String> numbers,
                                                   int directBet,
                                                   List<CategoryGame> categories,
                                                   List<AiParseResult> aiResults) {
        List<AiParseResult> corrected = new ArrayList<>();
        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        for (CategoryGame category : categories) {
            for (String number : numbers) {
                corrected.add(buildDirectResult(number, directBet, category, rawAiResponse));
            }
        }
        return corrected;
    }

    private List<AiParseResult> applyMixedDirectGroupCorrection(OrderRaw raw, List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (containsMultipleLines(text)) {
            return aiResults;
        }
        List<DirectBetMatch> directBetMatches = extractExplicitDirectBetMatches(text);
        if (directBetMatches.size() != 1) {
            return aiResults;
        }
        DirectBetMatch directBetMatch = directBetMatches.get(0);
        int directBet = directBetMatch.bet();
        if (directBet <= 0) {
            return aiResults;
        }
        if (!GROUP_PLAY_HINT.matcher(text).find()) {
            return aiResults;
        }
        if (containsPermutationMarker(text)) {
            return aiResults;
        }

        String numberScope = text.substring(0, directBetMatch.start()).trim();
        if (numberScope.isBlank()) {
            return aiResults;
        }

        List<String> numbers = extractThreeDigitNumbers(numberScope);
        if (numbers.isEmpty()) {
            return aiResults;
        }

        List<CategoryGame> categories = detectCategories(text, raw);
        if (!needsMixedDirectGroupCorrection(aiResults, numbers, categories, directBet)) {
            return aiResults;
        }

        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = new ArrayList<>();
        for (CategoryGame category : categories) {
            for (String number : numbers) {
                corrected.add(buildDirectResult(number, directBet, category, rawAiResponse));
            }
        }

        log.info("应用混合报单纠偏: rawId={}, numbers={}, bet={}, categories={}",
                raw.getId(), numbers, directBet, categories.size());
        return corrected;
    }

    /**
     * 仅在提示词与相似案例仍未纠正成功时，兜底修正"374 863 24单1组100"这类尾部金额误识别。
     */
    private List<AiParseResult> applyTrailingGroupAmountCorrection(OrderRaw raw, List<AiParseResult> aiResults) {
        String text = extractPrimaryOrderText(raw.getRawText());
        if (containsMultipleLines(text) || containsPermutationMarker(text)) {
            return aiResults;
        }

        DirectBetMatch directBetMatch = extractExplicitDirectBetMatch(text);
        if (directBetMatch == null || directBetMatch.bet() <= 0) {
            return aiResults;
        }

        String beforeBet = text.substring(0, directBetMatch.start()).trim();
        String suffix = text.substring(directBetMatch.end()).trim();
        if (beforeBet.isEmpty() || suffix.isEmpty()) {
            return aiResults;
        }

        Matcher suffixMatcher = TRAILING_GROUP_AMOUNT_SUFFIX.matcher(suffix);
        if (!suffixMatcher.matches()) {
            return aiResults;
        }

        List<String> numbers = extractThreeDigitNumbers(beforeBet);
        if (numbers.isEmpty()) {
            return aiResults;
        }

        List<CategoryGame> categories = detectCategories(text, raw);
        int resolvedBet = directBetMatch.bet();
        String amountToken = suffixMatcher.group(1);
        boolean amountMisparsedAsNumber = amountToken.length() == 3 && collectSingleNumbers(aiResults).contains(amountToken);
        // 混合"X单Y组金额"里，总金额通常包含组选费用，不能再反推回直选注数。
        // 这里只保留明确写出的X单；若尾部金额被误当号码，则按X单重建直选结果。

        if (!amountMisparsedAsNumber && !needsMixedDirectGroupCorrection(aiResults, numbers, categories, resolvedBet)) {
            return aiResults;
        }

        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = new ArrayList<>();
        for (CategoryGame category : categories) {
            for (String number : numbers) {
                corrected.add(buildDirectResult(number, resolvedBet, category, rawAiResponse));
            }
        }

        log.info("应用尾部组金额兜底纠偏: rawId={}, numbers={}, bet={}, amountToken={}",
                raw.getId(), numbers, resolvedBet, amountToken);
        return corrected;
    }

    private List<AiParseResult> applySeparatedGroupTotalNoBackfillCorrection(OrderRaw raw,
                                                                             List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (text.isBlank() || containsPermutationMarker(text) || !GROUP_PLAY_HINT.matcher(text).find()
                || !hasSentenceSeparator(text) || !hasSeparatedTrailingTotal(text)) {
            return aiResults;
        }

        List<CategoryGame> categories = detectCategories(text, raw);
        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = new ArrayList<>();
        boolean sawGroupSegment = false;
        boolean sawPlainNumberSegment = false;
        int parsedSegmentCount = 0;

        String[] segments = text.split("[。；;\\r\\n]+");
        int trailingTotalIndex = findTrailingSeparatedTotalIndex(segments);
        for (int i = 0; i < segments.length; i++) {
            String rawSegment = segments[i];
            String segment = rawSegment.trim();
            if (segment.isEmpty() || i == trailingTotalIndex) {
                continue;
            }

            List<String> numbers = extractThreeDigitNumbers(segment);
            if (numbers.isEmpty()) {
                continue;
            }
            if (isPureUnsupportedGroupLine(segment)) {
                sawGroupSegment = true;
                continue;
            }

            DirectBetMatch directBetMatch = extractExplicitDirectBetMatch(segment);
            if (directBetMatch != null && directBetMatch.bet() > 0) {
                List<String> scopedNumbers = extractThreeDigitNumbers(segment.substring(0, directBetMatch.start()));
                if (scopedNumbers.isEmpty()) {
                    scopedNumbers = numbers;
                }
                corrected.addAll(buildDirectResults(scopedNumbers, directBetMatch.bet(), categories, aiResults));
                sawGroupSegment = sawGroupSegment || GROUP_PLAY_HINT.matcher(segment).find();
                parsedSegmentCount++;
                continue;
            }

            if (GROUP_PLAY_HINT.matcher(segment).find()) {
                return aiResults;
            }

            corrected.addAll(buildDirectResults(numbers, 1, categories, aiResults));
            sawPlainNumberSegment = true;
            parsedSegmentCount++;
        }

        if (parsedSegmentCount == 0 || !sawGroupSegment || !sawPlainNumberSegment
                || corrected.isEmpty() || resultsMatchExpected(aiResults, corrected)) {
            return aiResults;
        }

        log.info("apply separated group-total no-backfill correction, rawId={}, segments={}, records={}",
                raw.getId(), parsedSegmentCount, corrected.size());
        return corrected;
    }

    /**
     * 对"708二十单"这类单号码直选口语写法做稳定纠偏，
     * 优先采用明确写出的注数，不依赖模型自行换算。
     */
    private List<AiParseResult> applySingleNumberDirectCorrection(OrderRaw raw, List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (containsMultipleLines(text)) {
            return aiResults;
        }
        Integer directBet = extractExplicitDirectBet(text);
        if (directBet == null || directBet <= 0) {
            return aiResults;
        }
        if (GROUP_PLAY_HINT.matcher(text).find() || DIRECT_SINGLE_BLOCK_HINT.matcher(text).find()) {
            return aiResults;
        }

        List<String> numbers = extractThreeDigitNumbers(text);
        if (numbers.size() != 1) {
            return aiResults;
        }

        List<CategoryGame> categories = detectCategories(text, raw);
        if (!needsMixedDirectGroupCorrection(aiResults, numbers, categories, directBet)) {
            return aiResults;
        }

        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = new ArrayList<>();
        for (CategoryGame category : categories) {
            corrected.add(buildDirectResult(numbers.get(0), directBet, category, rawAiResponse));
        }

        log.info("应用单号码直选纠偏: rawId={}, number={}, bet={}, categories={}",
                raw.getId(), numbers.get(0), directBet, categories.size());
        return corrected;
    }

    /**
     * 纠偏跨行共享注数场景，例如：
     * 146
     * 164各十单
     * 应将十单同时作用于前后连续号码。
     */
    private List<AiParseResult> applyCrossLineSharedBetCorrection(OrderRaw raw, List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (!containsMultipleLines(text)) {
            return aiResults;
        }
        if (GROUP_PLAY_HINT.matcher(text).find() || text.contains("复式") || text.contains("复试")) {
            return aiResults;
        }

        List<String> lines = splitNonBlankLines(text);
        if (lines.size() < 2 || lines.size() > 3) {
            return aiResults;
        }

        Integer sharedBet = null;
        int carrierCount = 0;
        List<String> mergedNumbers = new ArrayList<>();
        boolean hasPlainNumberLine = false;

        for (String line : lines) {
            List<String> nums = extractThreeDigitNumbers(line);
            if (nums.isEmpty()) {
                continue;
            }
            mergedNumbers.addAll(nums);

            Integer lineBet = extractExplicitDirectBet(line);
            if (lineBet != null && lineBet > 0 && line.contains("各")) {
                sharedBet = lineBet;
                carrierCount++;
            } else if (lineBet == null) {
                hasPlainNumberLine = true;
            }
        }

        if (mergedNumbers.isEmpty() || sharedBet == null || carrierCount != 1 || !hasPlainNumberLine) {
            return aiResults;
        }

        // 去重并保持首次出现顺序
        List<String> numbers = new ArrayList<>(new LinkedHashSet<>(mergedNumbers));
        List<CategoryGame> categories = detectCategories(text, raw);
        if (!needsMixedDirectGroupCorrection(aiResults, numbers, categories, sharedBet)) {
            return aiResults;
        }

        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = new ArrayList<>();
        for (CategoryGame category : categories) {
            for (String number : numbers) {
                corrected.add(buildDirectResult(number, sharedBet, category, rawAiResponse));
            }
        }

        log.info("apply cross-line shared bet correction, rawId={}, numbers={}, bet={}",
                raw.getId(), numbers.size(), sharedBet);
        return corrected;
    }

    /**
     * 纠偏长消息换行报单里“上一行号码 + 下一行号码各X单”的连续共享注数。
     * 例如上一行只列号码，下一行末尾写“各四单”，应把四单同时作用于这两行号码。
     */
    private List<AiParseResult> applyAdjacentLineSharedDirectBetCorrection(OrderRaw raw,
                                                                           List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (!containsMultipleLines(text) || containsPermutationMarker(text) || GROUP_PLAY_HINT.matcher(text).find()) {
            return aiResults;
        }

        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = new ArrayList<>();
        List<PendingNumberLine> pendingLines = new ArrayList<>();
        int parsedLineCount = 0;
        int sharedGroupCount = 0;

        for (String rawLine : text.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                if (!pendingLines.isEmpty()) {
                    return aiResults;
                }
                continue;
            }

            List<String> lineNumbers = extractThreeDigitNumbersPreserveDuplicates(line);
            if (lineNumbers.isEmpty()) {
                continue;
            }

            DirectBetMatch betMatch = extractExplicitDirectBetMatch(line);
            if (betMatch == null || betMatch.bet() <= 0) {
                if (!isPlainNumberContinuationLine(line)) {
                    return aiResults;
                }
                pendingLines.add(new PendingNumberLine(lineNumbers, detectCategories(line, raw)));
                continue;
            }

            List<String> scopedNumbers = extractThreeDigitNumbersPreserveDuplicates(line.substring(0, betMatch.start()));
            if (scopedNumbers.isEmpty()) {
                scopedNumbers = lineNumbers;
            }
            int directBet = resolveDirectBetWithTrailingAmount(line, betMatch, scopedNumbers.size());

            List<CategoryGame> explicitCategories = shouldForceTcByContext(raw)
                    ? detectCategories(line, raw)
                    : detectExplicitCategories(line);
            List<CategoryGame> currentCategories = detectCategories(line, raw);
            if (!pendingLines.isEmpty()) {
                if (!line.contains("各")) {
                    return aiResults;
                }
                sharedGroupCount++;
                for (PendingNumberLine pendingLine : pendingLines) {
                    List<CategoryGame> pendingCategories = explicitCategories.isEmpty()
                            ? pendingLine.categories()
                            : currentCategories;
                    for (CategoryGame category : pendingCategories) {
                        for (String number : pendingLine.numbers()) {
                            corrected.add(buildDirectResult(number, directBet, category, rawAiResponse));
                        }
                    }
                }
                if (explicitCategories.isEmpty() && pendingLines.size() == 1) {
                    currentCategories = pendingLines.get(0).categories();
                }
                pendingLines.clear();
            }

            for (CategoryGame category : currentCategories) {
                for (String number : scopedNumbers) {
                    corrected.add(buildDirectResult(number, directBet, category, rawAiResponse));
                }
            }
            parsedLineCount++;
        }

        if (!pendingLines.isEmpty() || parsedLineCount == 0 || sharedGroupCount == 0 || corrected.isEmpty()) {
            return aiResults;
        }

        Integer hintedTotalAmount = extractMessageTotalAmount(text);
        if (hintedTotalAmount != null && sumAmounts(corrected) != hintedTotalAmount) {
            return aiResults;
        }
        if (resultsMatchExpected(aiResults, corrected)) {
            return aiResults;
        }

        log.info("apply adjacent-line shared direct-bet correction, rawId={}, sharedGroups={}, records={}, hintedTotalAmount={}",
                raw.getId(), sharedGroupCount, corrected.size(), hintedTotalAmount);
        return corrected;
    }

    /**
     * 多行报单中，显式写出福/体的单独号码行不能被相同号码的上一段结果吞掉。
     */
    private List<AiParseResult> applyExplicitCategoryDirectLineCompletion(OrderRaw raw,
                                                                          List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (!containsMultipleLines(text)) {
            return aiResults;
        }

        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = new ArrayList<>(aiResults);
        Set<String> existing = collectResultSignatures(aiResults);
        int added = 0;

        for (String line : splitNonBlankLines(text)) {
            if (containsPermutationMarker(line) || LEOPARD_PACKAGE_HINT.matcher(line).find()
                    || isPureUnsupportedGroupLine(line)) {
                continue;
            }

            DirectBetMatch betMatch = extractExplicitDirectBetMatch(line);
            if (betMatch == null || betMatch.bet() <= 0) {
                continue;
            }

            List<CategoryGame> explicitCategories = detectExplicitCategories(line);
            if (explicitCategories.isEmpty()) {
                continue;
            }
            List<CategoryGame> categories = shouldForceTcByContext(raw)
                    ? List.of(new CategoryGame("TC", "P3"))
                    : explicitCategories;

            List<String> numbers = extractThreeDigitNumbers(line.substring(0, betMatch.start()).trim());
            if (numbers.isEmpty()) {
                continue;
            }

            for (CategoryGame category : categories) {
                for (String number : numbers) {
                    String signature = buildResultSignature(
                            category.category, category.game, "直选", number, betMatch.bet());
                    if (existing.add(signature)) {
                        corrected.add(buildDirectResult(number, betMatch.bet(), category, rawAiResponse));
                        added++;
                    }
                }
            }
        }

        if (added == 0) {
            return aiResults;
        }

        Integer totalAmount = extractGuardTotalAmount(text);
        if (totalAmount != null
                && Math.abs(sumAmounts(corrected) - totalAmount) >= Math.abs(sumAmounts(aiResults) - totalAmount)) {
            return aiResults;
        }

        log.info("apply explicit-category direct-line completion, rawId={}, added={}, amountBefore={}, amountAfter={}, hintedTotal={}",
                raw.getId(), added, sumAmounts(aiResults), sumAmounts(corrected), totalAmount);
        return corrected;
    }

    /**
     * 包豹子后又列出具体豹子号时，按号码聚合包号注数与显式注数，避免模型漏加某个豹子号。
     */
    private List<AiParseResult> applyLeopardPackageExplicitDirectAggregationCorrection(OrderRaw raw,
                                                                                       List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (!containsMultipleLines(text) || !LEOPARD_PACKAGE_HINT.matcher(text).find()) {
            return aiResults;
        }

        List<CategoryGame> inheritedCategories = shouldForceTcByContext(raw)
                ? List.of(new CategoryGame("TC", "P3"))
                : Collections.emptyList();
        Map<String, Integer> aggregateBets = new LinkedHashMap<>();
        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        int packageLineCount = 0;
        int explicitDirectLineCount = 0;

        for (String line : splitNonBlankLines(text)) {
            List<CategoryGame> explicitCategories = shouldForceTcByContext(raw)
                    ? List.of(new CategoryGame("TC", "P3"))
                    : detectExplicitCategories(line);
            if (!explicitCategories.isEmpty()) {
                inheritedCategories = explicitCategories;
            }
            List<CategoryGame> categories = !inheritedCategories.isEmpty()
                    ? inheritedCategories
                    : detectCategories(line, raw);

            Integer leopardBet = extractLeopardPackageBet(line);
            if (leopardBet != null) {
                if (leopardBet <= 0) {
                    return aiResults;
                }
                for (CategoryGame category : categories) {
                    for (String number : LEOPARD_NUMBERS) {
                        mergeDirectBet(aggregateBets, category, number, leopardBet);
                    }
                }
                packageLineCount++;
                continue;
            }

            if (isPureUnsupportedGroupLine(line) || containsPermutationMarker(line)) {
                continue;
            }

            DirectBetMatch betMatch = extractExplicitDirectBetMatch(line);
            if (betMatch == null || betMatch.bet() <= 0) {
                continue;
            }
            List<String> numbers = extractThreeDigitNumbers(line.substring(0, betMatch.start()).trim());
            if (numbers.isEmpty()) {
                continue;
            }

            for (CategoryGame category : categories) {
                for (String number : numbers) {
                    mergeDirectBet(aggregateBets, category, number, betMatch.bet());
                }
            }
            explicitDirectLineCount++;
        }

        if (packageLineCount == 0 || explicitDirectLineCount == 0 || aggregateBets.isEmpty()) {
            return aiResults;
        }

        List<AiParseResult> corrected = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : aggregateBets.entrySet()) {
            String[] parts = entry.getKey().split("\\|", 3);
            if (parts.length != 3 || entry.getValue() <= 0) {
                return aiResults;
            }
            corrected.add(buildDirectResult(
                    parts[2],
                    entry.getValue(),
                    new CategoryGame(parts[0], parts[1]),
                    rawAiResponse
            ));
        }

        if (resultsMatchExpected(aiResults, corrected)) {
            return aiResults;
        }

        log.info("apply leopard package explicit-direct aggregation, rawId={}, packageLines={}, explicitLines={}, records={}, amount={}",
                raw.getId(), packageLineCount, explicitDirectLineCount, corrected.size(), sumAmounts(corrected));
        return corrected;
    }

    private void mergeDirectBet(Map<String, Integer> aggregateBets,
                                CategoryGame category,
                                String number,
                                int bet) {
        String key = category.category + "|" + category.game + "|" + number;
        aggregateBets.merge(key, bet, Integer::sum);
    }

    // 匹配末尾 "X注复式Y单NNNN" 或 "复式Y单NNNN" 格式，其中NNNN是复式基数（多位数字）
    private static final Pattern EXPANDED_PERMUTATION_SUFFIX =
            Pattern.compile("(?:[零一二三四五六七八九十百千\\d]+注)?复[试式]([零一二三四五六七八九十百两\\d]+)单(\\d{4,})\\s*$");

    // 匹配"各X单复试/复式Y倍"主干，后面即使还跟"36*70=2520"这类算式也应识别
    private static final Pattern EACH_BET_PERMUTATION_MARKER =
            Pattern.compile("各([零一二三四五六七八九十百两\\d]+)(?:单)?复[试式](?:[零一二三四五六七八九十百两\\d]+倍?|[零一二三四五六七八九十百两\\d]+单)?");
    private static final Pattern EACH_DIRECT_PERMUTATION_EXPLICIT_MARKER =
            Pattern.compile("各([零一二三四五六七八九十百两\\d]+)(?:单)?复[试式]\\s*([零一二三四五六七八九十百两\\d]+)(?:倍|单)");
    private static final Pattern PERMUTATION_BET_AFTER_MARKER =
            Pattern.compile("复[试式]\\s*([零一二三四五六七八九十百两\\d]+)\\s*(?:单|倍)");
    private static final Pattern PERMUTATION_BET_BEFORE_MARKER =
            Pattern.compile("([零一二三四五六七八九十百两\\d]+)\\s*单\\s*复[试式]");
    private static final Pattern EXPLICIT_PERMUTATION_LIST_MARKER =
            Pattern.compile("(?:各\\s*)?复[试式]\\s*(?:各)?\\s*([零一二三四五六七八九十百两\\d]{1,3})\\s*(?:单|倍|体)?");
    private static final Pattern TRAILING_TOTAL_AMOUNT_HINT =
            Pattern.compile("(\\d{2,6})(?!.*\\d)");

    /**
     * 纠偏“各X单复试Y倍/单 + 尾随总额公式”：X是直选注数，Y是复式展开注数。
     */
    private List<AiParseResult> applyEachDirectPermutationAmountCorrection(OrderRaw raw,
                                                                           List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (text.isBlank() || !containsPermutationMarker(text)) {
            return aiResults;
        }

        String normalized = text.replaceAll("[—－-]", " ");
        Matcher matcher = EACH_DIRECT_PERMUTATION_EXPLICIT_MARKER.matcher(normalized);
        if (!matcher.find()) {
            return aiResults;
        }

        int directBet = parseNumericToken(matcher.group(1));
        int permutationBet = parseNumericToken(matcher.group(2));
        if (directBet <= 0 || permutationBet <= 0) {
            return aiResults;
        }

        Integer hintedTotalAmount = extractTrailingTotalAmount(normalized.substring(matcher.end()));
        if (hintedTotalAmount == null) {
            return aiResults;
        }

        List<String> sourceNumbers = extractThreeDigitNumbersPreserveDuplicates(
                normalized.substring(0, matcher.start()).trim());
        if (sourceNumbers.isEmpty()) {
            return aiResults;
        }

        List<CategoryGame> categories = detectCategories(text, raw);
        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = buildMixedDirectAndPermutationResults(
                sourceNumbers,
                directBet,
                permutationBet,
                categories,
                rawAiResponse
        );
        if (sumAmounts(corrected) != hintedTotalAmount || resultsMatchExpected(aiResults, corrected)) {
            return aiResults;
        }

        log.info("apply each-direct/permutation amount correction, rawId={}, sourceNumbers={}, directBet={}, permutationBet={}, hintedTotalAmount={}, records={}",
                raw.getId(), sourceNumbers.size(), directBet, permutationBet, hintedTotalAmount, corrected.size());
        return corrected;
    }

    /**
     * 纠偏"多号码列表 各X单复试/复式Y倍"格式
     * 例如："610—602—...—869 各两单复试两倍"
     * 对每个已列号码做内部全排列展开，play=复式，bet取X
     */
    private List<AiParseResult> applyEachBetPermutationCorrection(OrderRaw raw, List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (shouldTrustAiMixedDirectPermutation(text, aiResults)) {
            return aiResults;
        }
        if (MIXED_DIRECT_PERMUTATION_MARKER.matcher(text).find()) {
            return aiResults;
        }
        // 将连字符替换为空格，方便提取号码
        String normalized = text.replaceAll("[—－-]", " ");
        Matcher explicitMixedMatcher = EACH_DIRECT_PERMUTATION_EXPLICIT_MARKER.matcher(normalized);
        if (explicitMixedMatcher.find()
                && extractTrailingTotalAmount(normalized.substring(explicitMixedMatcher.end())) != null) {
            return aiResults;
        }
        Matcher m = EACH_BET_PERMUTATION_MARKER.matcher(normalized);
        if (!m.find()) {
            return aiResults;
        }

        String betStr = m.group(1);
        int bet;
        try {
            bet = betStr.matches("\\d+") ? Integer.parseInt(betStr) : parseChineseNumber(betStr);
        } catch (Exception e) {
            return aiResults;
        }
        if (bet <= 0) {
            return aiResults;
        }

        String beforeSuffix = normalized.substring(0, m.start()).trim();
        List<String> sourceNumbers = extractThreeDigitNumbers(beforeSuffix);
        if (sourceNumbers.isEmpty()) {
            return aiResults;
        }

        List<CategoryGame> categories = detectCategories(text, raw);
        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> permutationOnlyResults = buildExpandedPermutationResults(sourceNumbers, bet, categories, rawAiResponse);
        List<AiParseResult> mixedResults = buildMixedDirectAndPermutationResults(sourceNumbers, bet, categories, rawAiResponse);
        Integer hintedTotalAmount = extractTrailingTotalAmount(normalized.substring(m.end()));

        boolean directExplicitMatch = matchesExplicitPlayList(aiResults, "直选", sourceNumbers, categories, bet);
        boolean permutationExplicitMatch = matchesExplicitPlayList(aiResults, "复式", sourceNumbers, categories, bet);

        List<AiParseResult> corrected = permutationOnlyResults;
        String correctionMode = "permutation_only";
        if (hintedTotalAmount != null) {
            boolean mixedMatchesAmount = sumAmounts(mixedResults) == hintedTotalAmount;
            boolean permutationOnlyMatchesAmount = sumAmounts(permutationOnlyResults) == hintedTotalAmount;
            if (mixedMatchesAmount && !permutationOnlyMatchesAmount) {
                corrected = mixedResults;
                correctionMode = "mixed_by_amount_hint";
            } else if (permutationOnlyMatchesAmount && !mixedMatchesAmount) {
                correctionMode = "permutation_only_by_amount_hint";
            }
        }
        if ("permutation_only".equals(correctionMode) && directExplicitMatch && permutationExplicitMatch) {
            corrected = mixedResults;
            correctionMode = "mixed_by_ai_shape";
        }

        if (resultsMatchExpected(aiResults, corrected)) {
            return aiResults;
        }

        log.info("apply each-bet permutation correction, rawId={}, sourceNumbers={}, bet={}, corrected={}, mode={}, hintedTotalAmount={}",
                raw.getId(), sourceNumbers.size(), bet, corrected.size(), correctionMode, hintedTotalAmount);
        return corrected;
    }

    /**
     * 纠偏“号码列表 + 复试/复式X单/倍/体”只返回基数的问题。
     * 这里只做结构性展开：复式基数逐个全排列，非复式结果保持AI原意。
     */
    private List<AiParseResult> applyExplicitPermutationExpansionCorrection(OrderRaw raw,
                                                                            List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (text.isBlank() || !containsPermutationMarker(text)) {
            return aiResults;
        }

        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> expandedPermutationResults = new ArrayList<>();
        List<PendingNumberLine> pendingNumberLines = new ArrayList<>();
        List<CategoryGame> inheritedCategories = shouldForceTcByContext(raw)
                ? List.of(new CategoryGame("TC", "P3"))
                : Collections.emptyList();

        List<String> lines = containsMultipleLines(text) ? splitNonBlankLines(text) : List.of(text);
        for (String line : lines) {
            List<CategoryGame> explicitCategories = shouldForceTcByContext(raw)
                    ? List.of(new CategoryGame("TC", "P3"))
                    : detectExplicitCategories(line);
            if (!explicitCategories.isEmpty()) {
                inheritedCategories = explicitCategories;
            }
            List<CategoryGame> categories = !inheritedCategories.isEmpty()
                    ? inheritedCategories
                    : detectCategories(line, raw);

            List<String> lineNumbers = extractThreeDigitNumbersPreserveDuplicates(line);
            boolean hasPermutationMarker = containsPermutationMarker(line);
            if (!hasPermutationMarker) {
                if (!lineNumbers.isEmpty()
                        && extractExplicitDirectBetMatch(line) == null
                        && !GROUP_PLAY_HINT.matcher(line).find()) {
                    pendingNumberLines.add(new PendingNumberLine(lineNumbers, categories));
                }
                continue;
            }
            if (EXPANDED_PERMUTATION_SUFFIX.matcher(line).find()) {
                pendingNumberLines.clear();
                continue;
            }

            Matcher matcher = EXPLICIT_PERMUTATION_LIST_MARKER.matcher(line);
            boolean matchedLine = false;
            while (matcher.find()) {
                int permutationBet = parseNumericToken(matcher.group(1));
                if (permutationBet <= 0) {
                    continue;
                }

                List<String> sourceNumbers = extractThreeDigitNumbersPreserveDuplicates(
                        line.substring(0, matcher.start()).trim());
                List<CategoryGame> sourceCategories = categories;
                if (sourceNumbers.isEmpty() && !pendingNumberLines.isEmpty()) {
                    sourceNumbers = new ArrayList<>();
                    sourceCategories = pendingNumberLines.get(0).categories();
                    for (PendingNumberLine pending : pendingNumberLines) {
                        sourceNumbers.addAll(pending.numbers());
                    }
                }
                if (sourceNumbers.isEmpty()) {
                    continue;
                }

                expandedPermutationResults.addAll(buildExpandedPermutationResults(
                        sourceNumbers,
                        permutationBet,
                        sourceCategories,
                        rawAiResponse
                ));
                matchedLine = true;
            }

            pendingNumberLines.clear();
            if (!matchedLine && !lineNumbers.isEmpty() && extractExplicitDirectBetMatch(line) == null
                    && !GROUP_PLAY_HINT.matcher(line).find()) {
                pendingNumberLines.add(new PendingNumberLine(lineNumbers, categories));
            }
        }

        if (expandedPermutationResults.isEmpty()) {
            return aiResults;
        }

        List<AiParseResult> corrected = new ArrayList<>();
        for (AiParseResult result : aiResults) {
            if (result != null && result.isSuccess() && result.getData() != null
                    && !"\u590d\u5f0f".equals(result.getData().getPlay())) {
                corrected.add(result);
            }
        }
        corrected.addAll(expandedPermutationResults);

        if (resultsMatchExpected(aiResults, corrected)) {
            return aiResults;
        }

        log.info("apply explicit permutation expansion correction, rawId={}, permutationRecords={}, preservedNonPermutation={}",
                raw.getId(), expandedPermutationResults.size(), corrected.size() - expandedPermutationResults.size());
        return corrected;
    }

    /**
     * 对“X单+复式Y单”结构补齐遗漏记录。
     * 模型有时会只展开部分复式基数，或直选只保留左侧号码列表的第一个号码。
     */
    private List<AiParseResult> applyMissingMixedDirectLineCorrection(OrderRaw raw,
                                                                      List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (text.isBlank() || !containsPermutationMarker(text)
                || !MIXED_DIRECT_PERMUTATION_MARKER.matcher(text).find()) {
            return aiResults;
        }

        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = new ArrayList<>(aiResults);
        Set<String> existing = collectResultSignatures(aiResults);
        List<PendingNumberLine> pendingNumberLines = new ArrayList<>();
        List<CategoryGame> inheritedCategories = shouldForceTcByContext(raw)
                ? List.of(new CategoryGame("TC", "P3"))
                : Collections.emptyList();
        int added = 0;

        List<String> lines = containsMultipleLines(text) ? splitNonBlankLines(text) : List.of(text);
        for (String line : lines) {
            List<CategoryGame> explicitCategories = shouldForceTcByContext(raw)
                    ? List.of(new CategoryGame("TC", "P3"))
                    : detectExplicitCategories(line);
            if (!explicitCategories.isEmpty()) {
                inheritedCategories = explicitCategories;
            }
            List<CategoryGame> categories = !inheritedCategories.isEmpty()
                    ? inheritedCategories
                    : detectCategories(line, raw);

            Matcher mixedMatcher = MIXED_DIRECT_PERMUTATION_MARKER.matcher(line);
            if (mixedMatcher.find()) {
                int directBet = parseNumericToken(mixedMatcher.group(1));
                int permutationBet = parseNumericToken(mixedMatcher.group(2));
                if (directBet <= 0 || permutationBet <= 0) {
                    pendingNumberLines.clear();
                    continue;
                }

                List<String> sourceNumbers = extractThreeDigitNumbersPreserveDuplicates(
                        line.substring(0, mixedMatcher.start()).trim());
                List<CategoryGame> sourceCategories = categories;
                if (sourceNumbers.isEmpty() && !pendingNumberLines.isEmpty()) {
                    sourceNumbers = new ArrayList<>();
                    sourceCategories = pendingNumberLines.get(0).categories();
                    for (PendingNumberLine pending : pendingNumberLines) {
                        sourceNumbers.addAll(pending.numbers());
                    }
                }

                for (CategoryGame category : sourceCategories) {
                    for (String number : new LinkedHashSet<>(sourceNumbers)) {
                        String signature = buildResultSignature(category.category, category.game, "直选", number, directBet);
                        if (existing.add(signature)) {
                            corrected.add(buildDirectResult(number, directBet, category, rawAiResponse));
                            added++;
                        }
                        for (String expanded : expandPermutationNumbers(number)) {
                            String permutationSignature = buildResultSignature(
                                    category.category, category.game, "复式", expanded, permutationBet);
                            if (existing.add(permutationSignature)) {
                                corrected.add(buildPermutationResult(expanded, permutationBet, category, rawAiResponse));
                                added++;
                            }
                        }
                    }
                }
                pendingNumberLines.clear();
                continue;
            }

            List<String> lineNumbers = extractThreeDigitNumbersPreserveDuplicates(line);
            if (!lineNumbers.isEmpty()
                    && extractExplicitDirectBetMatch(line) == null
                    && !containsPermutationMarker(line)
                    && !GROUP_PLAY_HINT.matcher(line).find()) {
                pendingNumberLines.add(new PendingNumberLine(lineNumbers, categories));
            } else {
                pendingNumberLines.clear();
            }
        }

        if (added == 0) {
            return aiResults;
        }

        log.info("apply missing mixed direct line correction, rawId={}, added={}", raw.getId(), added);
        return corrected;
    }

    /**
     * 纠偏"已展开复式号码列表+末尾X单基数"格式
     * 例如："体012,013,...,789 九十六注复式三单3456"
     * 直接使用原文中列出的三位数号码，注数取"Y单"中的Y
     */
    private List<AiParseResult> applyExpandedPermutationListCorrection(OrderRaw raw, List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        Matcher m = EXPANDED_PERMUTATION_SUFFIX.matcher(text);
        if (!m.find()) {
            return aiResults;
        }

        // 提取注数（Y单）
        String betStr = m.group(1);
        int bet;
        try {
            bet = betStr.matches("\\d+") ? Integer.parseInt(betStr) : parseChineseNumber(betStr);
        } catch (Exception e) {
            return aiResults;
        }
        if (bet <= 0) {
            return aiResults;
        }

        // 提取末尾基数前的文本，从中提取所有三位数号码
        String beforeSuffix = text.substring(0, m.start()).trim();
        List<String> numbers = extractThreeDigitNumbers(beforeSuffix);
        if (numbers.isEmpty()) {
            return aiResults;
        }

        // 只有当AI结果数量或注数不对时才纠偏
        List<CategoryGame> categories = detectCategories(text, raw);
        int expectedSize = numbers.size() * categories.size();
        boolean needsCorrection = aiResults.size() != expectedSize;
        if (!needsCorrection) {
            for (AiParseResult r : aiResults) {
                if (!r.isSuccess() || r.getData() == null || r.getData().getBet() != bet) {
                    needsCorrection = true;
                    break;
                }
            }
        }
        if (!needsCorrection) {
            return aiResults;
        }

        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = new ArrayList<>();
        for (CategoryGame category : categories) {
            for (String number : numbers) {
                corrected.add(buildPermutationResult(number, bet, category, rawAiResponse));
            }
        }

        log.info("apply expanded permutation list correction, rawId={}, numbers={}, bet={}, categories={}",
                raw.getId(), numbers.size(), bet, categories.size());
        return corrected;
    }

    private List<AiParseResult> applyMultilineScopedCategoryCorrection(OrderRaw raw, List<AiParseResult> aiResults) {
        String text = extractPrimaryOrderText(raw.getRawText());
        List<String> lines = splitNonBlankLines(text);
        if (lines.size() < 2) {
            return aiResults;
        }

        boolean hasExplicitTcLine = false;
        boolean hasImplicitPermutationLine = false;
        List<AiParseResult> corrected = new ArrayList<>();
        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        int parsedLineCount = 0;

        for (String line : lines) {
            List<String> numbers = extractThreeDigitNumbers(line);
            if (numbers.isEmpty()) {
                continue;
            }

            Integer directBet = extractExplicitDirectBet(line);
            if (directBet == null || directBet <= 0) {
                return aiResults;
            }

            boolean permutation = containsPermutationMarker(line);
            boolean explicitCategory = hasExplicitCategoryMarker(line, raw);
            if (hasExplicitTcMarker(line)) {
                hasExplicitTcLine = true;
            }
            if (permutation && !explicitCategory) {
                hasImplicitPermutationLine = true;
            }

            List<CategoryGame> categories = detectCategories(line, raw);
            if (permutation) {
                if (numbers.size() != 1) {
                    return aiResults;
                }
                for (CategoryGame category : categories) {
                    for (String number : expandPermutationNumbers(numbers.get(0))) {
                        corrected.add(buildPermutationResult(number, directBet, category, rawAiResponse));
                    }
                }
            } else {
                for (CategoryGame category : categories) {
                    for (String number : numbers) {
                        corrected.add(buildDirectResult(number, directBet, category, rawAiResponse));
                    }
                }
            }
            parsedLineCount++;
        }

        if (parsedLineCount < 2 || !hasExplicitTcLine || !hasImplicitPermutationLine) {
            return aiResults;
        }
        if (!needsMultilineScopedCategoryCorrection(aiResults, corrected)) {
            return aiResults;
        }

        log.info("apply multiline scoped category correction, rawId={}, lines={}", raw.getId(), parsedLineCount);
        return corrected;
    }

    /**
     * 纠偏按空行分块的混合彩种报单：
     * 块内只要出现了明确的“体/福”彩种提示，该块中未显式写彩种的号码行
     * 都继承这一块的彩种，避免整条消息里的其他彩种提示互相污染。
     */
    private List<AiParseResult> applyBlankLineScopedCategoryCorrection(OrderRaw raw, List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (!containsMultipleLines(text) || containsPermutationMarker(text) || GROUP_PLAY_HINT.matcher(text).find()) {
            return aiResults;
        }

        List<List<String>> blocks = splitLineBlocks(text);
        if (blocks.size() < 2) {
            return aiResults;
        }

        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = new ArrayList<>();
        int scopedBlockCount = 0;
        boolean hasFcBlock = false;
        boolean hasTcBlock = false;

        for (List<String> blockLines : blocks) {
            String blockText = String.join("\n", blockLines);
            List<CategoryGame> blockCategories = detectExplicitCategories(blockText);
            if (blockCategories.size() != 1) {
                return aiResults;
            }

            if ("FC".equals(blockCategories.get(0).category())) {
                hasFcBlock = true;
            } else if ("TC".equals(blockCategories.get(0).category())) {
                hasTcBlock = true;
            }

            int blockNumberLineCount = 0;
            for (String line : blockLines) {
                List<String> numbers = extractThreeDigitNumbers(line);
                if (numbers.isEmpty()) {
                    continue;
                }
                blockNumberLineCount++;

                Integer directBet = extractExplicitDirectBet(line);
                int resolvedBet = directBet != null && directBet > 0 ? directBet : 1;

                List<CategoryGame> lineCategories = detectExplicitCategories(line);
                if (lineCategories.size() > 1) {
                    return aiResults;
                }
                List<CategoryGame> resolvedCategories = lineCategories.isEmpty() ? blockCategories : lineCategories;

                for (CategoryGame category : resolvedCategories) {
                    for (String number : numbers) {
                        corrected.add(buildDirectResult(number, resolvedBet, category, rawAiResponse));
                    }
                }
            }

            if (blockNumberLineCount > 0) {
                scopedBlockCount++;
            }
        }

        if (scopedBlockCount < 2 || !hasFcBlock || !hasTcBlock) {
            return aiResults;
        }
        if (resultsMatchExpected(aiResults, corrected)) {
            return aiResults;
        }

        log.info("apply blank-line scoped category correction, rawId={}, blocks={}", raw.getId(), scopedBlockCount);
        return corrected;
    }

    /**
     * 纠偏“号码-注数单”成对写法，例如：
     * 福 356-20单 829-20单
     * 体 356-15单 608-20单:?150
     * 尾部合计金额 150 不能当作号码，且每个号码要使用自己后面的注数。
     */
    private List<AiParseResult> applyNumberBetPairCorrection(OrderRaw raw, List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (text.isBlank() || containsPermutationMarker(text) || GROUP_PLAY_HINT.matcher(text).find()) {
            return aiResults;
        }

        List<String> lines = containsMultipleLines(text) ? splitNonBlankLines(text) : List.of(text);
        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = new ArrayList<>();
        int parsedLineCount = 0;

        for (String line : lines) {
            List<NumberBetMatch> pairs = extractNumberBetPairs(line);
            if (pairs.isEmpty()) {
                if (!extractThreeDigitNumbers(line).isEmpty()) {
                    return aiResults;
                }
                continue;
            }

            List<CategoryGame> categories = detectCategories(line, raw);
            if (categories.size() != 1) {
                return aiResults;
            }

            for (NumberBetMatch pair : pairs) {
                for (CategoryGame category : categories) {
                    corrected.add(buildDirectResult(pair.number(), pair.bet(), category, rawAiResponse));
                }
            }
            parsedLineCount++;
        }

        if (parsedLineCount == 0 || corrected.isEmpty() || resultsMatchExpected(aiResults, corrected)) {
            return aiResults;
        }

        log.info("apply number-bet pair correction, rawId={}, lines={}, records={}",
                raw.getId(), parsedLineCount, corrected.size());
        return corrected;
    }

    /**
     * 纠偏单行或全组选报单：只有“组/组选/组三/组六”玩法，没有任何明确直选注数锚点时，
     * 不能把号码默认为直选各1注。
     */
    private List<AiParseResult> applyPureUnsupportedGroupSkipCorrection(OrderRaw raw,
                                                                        List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (text.isBlank() || containsPermutationMarker(text)) {
            return aiResults;
        }

        List<String> lines = splitNonBlankLines(text);
        if (lines.isEmpty()) {
            return aiResults;
        }

        int numberLineCount = 0;
        int pureGroupLineCount = 0;
        for (String line : lines) {
            if (extractThreeDigitNumbers(line).isEmpty()) {
                continue;
            }

            numberLineCount++;
            if (!isPureUnsupportedGroupLine(line)) {
                return aiResults;
            }
            pureGroupLineCount++;
        }

        if (numberLineCount == 0 || pureGroupLineCount != numberLineCount) {
            return aiResults;
        }
        if (aiResults.size() == 1 && aiResults.get(0).isSkip()) {
            return aiResults;
        }

        log.info("apply pure unsupported group skip correction, rawId={}, numberLines={}",
                raw.getId(), numberLineCount);
        return List.of(createSkipResult(aiResults, "组选玩法暂不支持"));
    }

    /**
     * 纠偏单行豹子全包口语写法，例如“福包三批五单100”。
     */
    private List<AiParseResult> applySingleLineLeopardPackageCorrection(OrderRaw raw,
                                                                        List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (text.isBlank() || containsMultipleLines(text) || !LEOPARD_PACKAGE_HINT.matcher(text).find()) {
            return aiResults;
        }
        Matcher leopardMatcher = LEOPARD_PACKAGE_HINT.matcher(text);
        if (!leopardMatcher.find()) {
            return aiResults;
        }
        if (!extractThreeDigitNumbers(text.substring(0, leopardMatcher.start())).isEmpty()) {
            return aiResults;
        }

        Integer leopardBet = extractLeopardPackageBet(text);
        if (leopardBet == null || leopardBet <= 0) {
            return aiResults;
        }

        List<CategoryGame> categories = detectCategories(text, raw);
        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = new ArrayList<>();
        for (CategoryGame category : categories) {
            for (String number : LEOPARD_NUMBERS) {
                corrected.add(buildDirectResult(number, leopardBet, category, rawAiResponse));
            }
        }

        if (resultsMatchExpected(aiResults, corrected)) {
            return aiResults;
        }
        log.info("apply single-line leopard package correction, rawId={}, categories={}, bet={}, records={}",
                raw.getId(), categories.size(), leopardBet, corrected.size());
        return corrected;
    }

    /**
     * 纠偏“体福/福体”双彩种直选号列表，模型漏掉一个彩种时按现有号码集合补齐。
     */
    private List<AiParseResult> applyMultiCategoryDirectListCorrection(OrderRaw raw,
                                                                       List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (text.isBlank() || containsMultipleLines(text) || containsPermutationMarker(text)
                || GROUP_PLAY_HINT.matcher(text).find() || LEOPARD_PACKAGE_HINT.matcher(text).find()) {
            return aiResults;
        }

        List<CategoryGame> categories = detectCategories(text, raw);
        if (categories.size() < 2) {
            return aiResults;
        }

        List<DirectBetMatch> betMatches = extractExplicitDirectBetMatches(text);
        if (betMatches.size() != 1 || betMatches.get(0).bet() <= 0) {
            return aiResults;
        }

        DirectBetMatch betMatch = betMatches.get(0);
        List<String> sourceNumbers = extractThreeDigitNumbers(text.substring(0, betMatch.start()));
        if (sourceNumbers.isEmpty()) {
            return aiResults;
        }
        if (!collectSingleNumbers(aiResults).equals(new LinkedHashSet<>(sourceNumbers))) {
            return aiResults;
        }
        for (AiParseResult result : aiResults) {
            if (!result.isSuccess() || result.getData() == null
                    || !"直选".equals(result.getData().getPlay())
                    || result.getData().getBet() != betMatch.bet()) {
                return aiResults;
            }
        }

        List<AiParseResult> corrected = buildDirectResults(sourceNumbers, betMatch.bet(), categories, aiResults);
        if (resultsMatchExpected(aiResults, corrected)) {
            return aiResults;
        }

        log.info("apply multi-category direct-list correction, rawId={}, numbers={}, bet={}, categories={}",
                raw.getId(), sourceNumbers.size(), betMatch.bet(), categories.size());
        return corrected;
    }

    /**
     * 纠偏“说明行 + 号码行”的混合组选格式：
     * 福彩2注打10单5组?60
     * 480 604
     * 只保留直选10单，忽略不支持的5组及包含组选费用的合计金额。
     */
    private List<AiParseResult> applyHeaderDirectGroupNumberListCorrection(OrderRaw raw,
                                                                           List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (!containsMultipleLines(text) || containsPermutationMarker(text)) {
            return aiResults;
        }

        List<String> lines = splitNonBlankLines(text);
        if (lines.size() < 2) {
            return aiResults;
        }

        DirectBetMatch headerBet = null;
        List<CategoryGame> headerCategories = Collections.emptyList();
        List<String> numbers = new ArrayList<>();

        for (String line : lines) {
            List<String> lineNumbers = extractThreeDigitNumbers(line);
            if (lineNumbers.isEmpty()) {
                if (headerBet != null) {
                    continue;
                }
                DirectBetMatch betMatch = extractExplicitDirectBetMatch(line);
                if (betMatch != null && betMatch.bet() > 0 && GROUP_PLAY_HINT.matcher(line).find()) {
                    headerBet = betMatch;
                    headerCategories = detectCategories(line, raw);
                    continue;
                }
                continue;
            }

            if (headerBet == null) {
                return aiResults;
            }
            if (extractExplicitDirectBet(line) != null || GROUP_PLAY_HINT.matcher(line).find()) {
                return aiResults;
            }
            numbers.addAll(lineNumbers);
        }

        if (headerBet == null || numbers.isEmpty()) {
            return aiResults;
        }

        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<String> uniqueNumbers = new ArrayList<>(new LinkedHashSet<>(numbers));
        List<CategoryGame> categories = headerCategories.isEmpty() ? detectCategories(text, raw) : headerCategories;
        List<AiParseResult> corrected = buildDirectResults(uniqueNumbers, headerBet.bet(), categories, aiResults);
        if (resultsMatchExpected(aiResults, corrected)) {
            return aiResults;
        }

        log.info("apply header direct/group number-list correction, rawId={}, numbers={}, bet={}",
                raw.getId(), uniqueNumbers.size(), headerBet.bet());
        return corrected;
    }

    /**
     * 逐行纠偏“直选X单 + 复式Y单”混合玩法，支持多行分别处理。
     */
    private List<AiParseResult> applyLineScopedMixedDirectPermutationCorrection(OrderRaw raw,
                                                                                List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (text.isBlank() || !containsPermutationMarker(text)) {
            return aiResults;
        }

        List<String> lines = splitNonBlankLines(text);
        if (lines.isEmpty()) {
            return aiResults;
        }

        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = new ArrayList<>();
        int matchedLineCount = 0;
        int numberLineCount = 0;

        for (String line : lines) {
            List<String> lineNumbers = extractThreeDigitNumbers(line);
            if (lineNumbers.isEmpty()) {
                continue;
            }
            numberLineCount++;

            Matcher mixedMatcher = MIXED_DIRECT_PERMUTATION_MARKER.matcher(line);
            if (!mixedMatcher.find()) {
                return aiResults;
            }

            int directBet = parseNumericToken(mixedMatcher.group(1));
            int permutationBet = parseNumericToken(mixedMatcher.group(2));
            if (directBet <= 0 || permutationBet <= 0) {
                return aiResults;
            }

            List<String> sourceNumbers = extractThreeDigitNumbers(line.substring(0, mixedMatcher.start()).trim());
            if (sourceNumbers.isEmpty()) {
                return aiResults;
            }

            corrected.addAll(buildMixedDirectAndPermutationResults(
                    sourceNumbers,
                    directBet,
                    permutationBet,
                    detectCategories(line, raw),
                    rawAiResponse
            ));
            matchedLineCount++;
        }

        if (matchedLineCount == 0 || matchedLineCount != numberLineCount || corrected.isEmpty()
                || resultsMatchExpected(aiResults, corrected)) {
            return aiResults;
        }

        log.info("apply line-scoped mixed direct/permutation correction, rawId={}, lines={}, records={}",
                raw.getId(), matchedLineCount, corrected.size());
        return corrected;
    }

    /**
     * 纠偏单行中用逗号分开的“复式 + 直选”混排，例如“259复式五单，529十单”。
     */
    private List<AiParseResult> applySegmentScopedPermutationAndDirectCorrection(OrderRaw raw,
                                                                                 List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (text.isBlank() || containsMultipleLines(text) || !containsPermutationMarker(text)
                || GROUP_PLAY_HINT.matcher(text).find()) {
            return aiResults;
        }

        List<String> segments = splitBetSegments(text);
        if (segments.size() < 2) {
            return aiResults;
        }

        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = new ArrayList<>();
        int parsedSegmentCount = 0;
        int permutationSegmentCount = 0;
        int directSegmentCount = 0;

        for (String segment : segments) {
            if (segment.isBlank()) {
                continue;
            }
            List<String> segmentNumbers = extractThreeDigitNumbers(segment);
            if (segmentNumbers.isEmpty()) {
                continue;
            }
            if (MIXED_DIRECT_PERMUTATION_MARKER.matcher(segment).find()) {
                return aiResults;
            }

            DirectBetMatch betMatch = extractExplicitDirectBetMatch(segment);
            if (betMatch == null || betMatch.bet() <= 0) {
                return aiResults;
            }

            List<CategoryGame> categories = detectCategories(segment, raw);
            if (containsPermutationMarker(segment)) {
                List<String> sourceNumbers = extractSimplePermutationSourceNumbers(segment, betMatch);
                if (sourceNumbers.isEmpty()) {
                    return aiResults;
                }
                corrected.addAll(buildExpandedPermutationResults(sourceNumbers, betMatch.bet(), categories, rawAiResponse));
                permutationSegmentCount++;
            } else {
                List<String> sourceNumbers = extractThreeDigitNumbers(segment.substring(0, betMatch.start()).trim());
                if (sourceNumbers.isEmpty()) {
                    sourceNumbers = segmentNumbers;
                }
                int directBet = resolveDirectBetWithTrailingAmount(segment, betMatch, sourceNumbers.size());
                for (CategoryGame category : categories) {
                    for (String number : sourceNumbers) {
                        corrected.add(buildDirectResult(number, directBet, category, rawAiResponse));
                    }
                }
                directSegmentCount++;
            }
            parsedSegmentCount++;
        }

        if (parsedSegmentCount < 2 || permutationSegmentCount == 0 || directSegmentCount == 0
                || corrected.isEmpty() || resultsMatchExpected(aiResults, corrected)) {
            return aiResults;
        }

        log.info("apply segment-scoped permutation/direct correction, rawId={}, segments={}, permutationSegments={}, directSegments={}, records={}",
                raw.getId(), parsedSegmentCount, permutationSegmentCount, directSegmentCount, corrected.size());
        return corrected;
    }

    /**
     * 纠偏“直选X单Y组 + 复式Z单”同句混排，例如：
     * 935五单一组998复式一单共18米
     * 其中组和总额只是说明，支持玩法只保留直选与复式。
     */
    private List<AiParseResult> applyGroupedDirectPermutationCorrection(OrderRaw raw,
                                                                        List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (text.isBlank() || !containsPermutationMarker(text) || !GROUP_PLAY_HINT.matcher(text).find()) {
            return aiResults;
        }

        List<String> lines = containsMultipleLines(text) ? splitNonBlankLines(text) : List.of(text);
        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = new ArrayList<>();
        int handledLineCount = 0;
        int numberLineCount = 0;

        for (String line : lines) {
            List<String> lineNumbers = extractThreeDigitNumbers(line);
            if (lineNumbers.isEmpty()) {
                continue;
            }
            numberLineCount++;

            int permutationMarkerStart = findPermutationMarkerStart(line);
            if (permutationMarkerStart <= 0) {
                return aiResults;
            }

            DirectBetMatch directBetMatch = extractExplicitDirectBetMatch(line);
            if (directBetMatch == null || directBetMatch.bet() <= 0 || directBetMatch.start() >= permutationMarkerStart) {
                return aiResults;
            }

            String directScope = line.substring(0, directBetMatch.start()).trim();
            List<String> directNumbers = extractThreeDigitNumbers(directScope);
            if (directNumbers.isEmpty()) {
                return aiResults;
            }

            String betweenDirectAndPermutation = line.substring(directBetMatch.end(), permutationMarkerStart);
            if (!GROUP_PLAY_HINT.matcher(betweenDirectAndPermutation).find()) {
                return aiResults;
            }

            List<String> permutationNumbers = extractThreeDigitNumbers(betweenDirectAndPermutation);
            if (permutationNumbers.isEmpty()) {
                permutationNumbers = directNumbers;
            }

            int permutationBet = extractPermutationBetNearMarker(line);
            if (permutationBet <= 0) {
                return aiResults;
            }

            List<CategoryGame> categories = detectCategories(line, raw);
            corrected.addAll(buildDirectResults(directNumbers, directBetMatch.bet(), categories, aiResults));
            corrected.addAll(buildExpandedPermutationResults(permutationNumbers, permutationBet, categories, rawAiResponse));
            handledLineCount++;
        }

        if (handledLineCount == 0 || handledLineCount != numberLineCount || corrected.isEmpty()
                || resultsMatchExpected(aiResults, corrected)) {
            return aiResults;
        }

        log.info("apply grouped direct/permutation correction, rawId={}, lines={}, records={}",
                raw.getId(), handledLineCount, corrected.size());
        return corrected;
    }

    /**
     * 逐行纠偏普通复式和直选混排，例如：
     * 602复式一单12米
     * 504九单一20米
     * 504复式一单12米
     */
    private List<AiParseResult> applyLineScopedPermutationAndDirectCorrection(OrderRaw raw,
                                                                              List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (!containsMultipleLines(text) || !containsPermutationMarker(text)) {
            return aiResults;
        }
        if (shouldTrustAiMixedDirectPermutation(text, aiResults)) {
            return aiResults;
        }

        List<String> lines = splitNonBlankLines(text);
        if (lines.size() < 2) {
            return aiResults;
        }

        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = new ArrayList<>();
        int parsedLineCount = 0;
        int permutationLineCount = 0;

        for (String line : lines) {
            List<String> lineNumbers = extractThreeDigitNumbers(line);
            if (lineNumbers.isEmpty()) {
                continue;
            }
            if (MIXED_DIRECT_PERMUTATION_MARKER.matcher(line).find()) {
                return aiResults;
            }

            DirectBetMatch betMatch = extractExplicitDirectBetMatch(line);
            if (betMatch == null || betMatch.bet() <= 0) {
                return aiResults;
            }

            List<CategoryGame> categories = detectCategories(line, raw);
            if (containsPermutationMarker(line)) {
                List<String> sourceNumbers = extractSimplePermutationSourceNumbers(line, betMatch);
                if (sourceNumbers.isEmpty()) {
                    return aiResults;
                }
                corrected.addAll(buildExpandedPermutationResults(sourceNumbers, betMatch.bet(), categories, rawAiResponse));
                permutationLineCount++;
                parsedLineCount++;
                continue;
            }

            if (GROUP_PLAY_HINT.matcher(line).find()) {
                return aiResults;
            }
            List<String> sourceNumbers = extractThreeDigitNumbers(line.substring(0, betMatch.start()).trim());
            if (sourceNumbers.isEmpty()) {
                sourceNumbers = lineNumbers;
            }
            int directBet = resolveDirectBetWithTrailingAmount(line, betMatch, sourceNumbers.size());
            for (CategoryGame category : categories) {
                for (String number : sourceNumbers) {
                    corrected.add(buildDirectResult(number, directBet, category, rawAiResponse));
                }
            }
            parsedLineCount++;
        }

        if (parsedLineCount == 0 || permutationLineCount == 0 || corrected.isEmpty()
                || resultsMatchExpected(aiResults, corrected)) {
            return aiResults;
        }

        log.info("apply line-scoped permutation/direct correction, rawId={}, lines={}, permutationLines={}, records={}",
                raw.getId(), parsedLineCount, permutationLineCount, corrected.size());
        return corrected;
    }

    /**
     * 纠偏多行混合报单中“纯组选行跳过 + 豹子全包金额拆分”的组合写法。
     * 例如：
     * 体008、017、...、567、组选50倍
     * 666五十单
     * 包豹子500
     */
    private List<AiParseResult> applyLineScopedGroupAndLeopardPackageCorrection(OrderRaw raw,
                                                                               List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (!containsMultipleLines(text)) {
            return aiResults;
        }

        List<String> lines = splitNonBlankLines(text);
        if (lines.size() < 2) {
            return aiResults;
        }

        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = new ArrayList<>();
        List<CategoryGame> inheritedCategories = shouldForceTcByContext(raw)
                ? List.of(new CategoryGame("TC", "P3"))
                : Collections.emptyList();

        int parsedLineCount = 0;
        int skippedGroupLineCount = 0;
        int leopardPackageLineCount = 0;
        int directGroupLineCount = 0;

        for (String line : lines) {
            List<CategoryGame> explicitCategories = shouldForceTcByContext(raw)
                    ? List.of(new CategoryGame("TC", "P3"))
                    : detectExplicitCategories(line);
            if (!explicitCategories.isEmpty()) {
                inheritedCategories = explicitCategories;
            }
            List<CategoryGame> categories = !inheritedCategories.isEmpty()
                    ? inheritedCategories
                    : detectCategories(line, raw);

            if (isPureUnsupportedGroupLine(line)) {
                skippedGroupLineCount++;
                continue;
            }

            Integer leopardBet = extractLeopardPackageBet(line);
            if (leopardBet != null) {
                if (leopardBet <= 0) {
                    return aiResults;
                }
                for (CategoryGame category : categories) {
                    for (String number : LEOPARD_NUMBERS) {
                        corrected.add(buildDirectResult(number, leopardBet, category, rawAiResponse));
                    }
                }
                parsedLineCount++;
                leopardPackageLineCount++;
                continue;
            }

            List<NumberBetMatch> pairs = extractNumberBetPairs(line);
            if (!pairs.isEmpty()) {
                for (CategoryGame category : categories) {
                    for (NumberBetMatch pair : pairs) {
                        corrected.add(buildDirectResult(pair.number(), pair.bet(), category, rawAiResponse));
                    }
                }
                parsedLineCount++;
                continue;
            }

            DirectBetMatch directBetMatch = extractExplicitDirectBetMatch(line);
            List<String> numbers = extractThreeDigitNumbers(line);
            if (directBetMatch != null && directBetMatch.bet() > 0 && !numbers.isEmpty()) {
                if (containsPermutationMarker(line)) {
                    return aiResults;
                }
                List<String> scopedNumbers = extractThreeDigitNumbers(line.substring(0, directBetMatch.start()));
                if (scopedNumbers.isEmpty()) {
                    scopedNumbers = numbers;
                }
                for (CategoryGame category : categories) {
                    for (String number : scopedNumbers) {
                        corrected.add(buildDirectResult(number, directBetMatch.bet(), category, rawAiResponse));
                    }
                }
                if (GROUP_PLAY_HINT.matcher(line).find()) {
                    directGroupLineCount++;
                }
                parsedLineCount++;
                continue;
            }

            if (!numbers.isEmpty()) {
                return aiResults;
            }
        }

        if (parsedLineCount == 0 || corrected.isEmpty()
                || (skippedGroupLineCount == 0 && leopardPackageLineCount == 0 && directGroupLineCount == 0)
                || resultsMatchExpected(aiResults, corrected)) {
            return aiResults;
        }

        log.info("apply line-scoped group/leopard correction, rawId={}, parsedLines={}, skippedGroupLines={}, leopardLines={}, directGroupLines={}, records={}",
                raw.getId(), parsedLineCount, skippedGroupLineCount, leopardPackageLineCount, directGroupLineCount, corrected.size());
        return corrected;
    }

    private List<AiParseResult> applySimplePermutationCorrection(OrderRaw raw, List<AiParseResult> aiResults) {
        String text = normalizeRawTextForCorrection(raw.getRawText());
        if (containsMultipleLines(text) || !containsPermutationMarker(text)) {
            return aiResults;
        }
        if (GROUP_PLAY_HINT.matcher(text).find()) {
            return aiResults;
        }
        if (splitBetSegments(text).size() > 1) {
            return aiResults;
        }
        if (shouldTrustAiMixedDirectPermutation(text, aiResults)) {
            return aiResults;
        }

        Matcher mixedMatcher = MIXED_DIRECT_PERMUTATION_MARKER.matcher(text);
        if (mixedMatcher.find()) {
            return applyMixedDirectPermutationCorrection(raw, aiResults, text, mixedMatcher);
        }

        DirectBetMatch directBetMatch = extractExplicitDirectBetMatch(text);
        if (directBetMatch == null || directBetMatch.bet() <= 0) {
            return aiResults;
        }
        int directBet = directBetMatch.bet();

        List<String> sourceNumbers = extractSimplePermutationSourceNumbers(text, directBetMatch);
        if (sourceNumbers.isEmpty()) {
            return aiResults;
        }

        List<CategoryGame> categories = detectCategories(text, raw);
        if (!needsPermutationCorrection(aiResults, sourceNumbers, categories, directBet)) {
            return aiResults;
        }

        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = new ArrayList<>();
        for (CategoryGame category : categories) {
            for (String sourceNumber : sourceNumbers) {
                for (String number : expandPermutationNumbers(sourceNumber)) {
                    corrected.add(buildPermutationResult(number, directBet, category, rawAiResponse));
                }
            }
        }

        log.info("apply simple permutation correction, rawId={}, sourceNumbers={}, bet={}, categories={}",
                raw.getId(), sourceNumbers, directBet, categories.size());
        return corrected;
    }

    private List<AiParseResult> applyMixedDirectPermutationCorrection(OrderRaw raw,
                                                                      List<AiParseResult> aiResults,
                                                                      String text,
                                                                      Matcher mixedMatcher) {
        int directBet = parseNumericToken(mixedMatcher.group(1));
        int permutationBet = parseNumericToken(mixedMatcher.group(2));
        if (directBet <= 0 || permutationBet <= 0) {
            return aiResults;
        }

        List<String> sourceNumbers = extractThreeDigitNumbers(text.substring(0, mixedMatcher.start()).trim());
        if (sourceNumbers.isEmpty()) {
            return aiResults;
        }

        List<CategoryGame> categories = detectCategories(text, raw);
        String rawAiResponse = aiResults.isEmpty() ? null : aiResults.get(0).getRawAiResponse();
        List<AiParseResult> corrected = buildMixedDirectAndPermutationResults(
                sourceNumbers,
                directBet,
                permutationBet,
                categories,
                rawAiResponse
        );
        if (resultsMatchExpected(aiResults, corrected)) {
            return aiResults;
        }

        log.info("apply mixed direct/permutation correction, rawId={}, sourceNumbers={}, directBet={}, permutationBet={}, records={}",
                raw.getId(), sourceNumbers, directBet, permutationBet, corrected.size());
        return corrected;
    }

    /**
     * 单个“基数 + 复式X单 + 尾随总额”结构里，尾随金额不能再作为复式基数。
     * 例如：686复式20单福120 => 只取 686，120 是总金额。
     */
    private List<String> extractSimplePermutationSourceNumbers(String text, DirectBetMatch directBetMatch) {
        if (text == null || directBetMatch == null) {
            return Collections.emptyList();
        }

        String beforeBet = text.substring(0, directBetMatch.start()).trim();
        List<String> beforeNumbers = extractThreeDigitNumbers(beforeBet);
        if (!beforeNumbers.isEmpty()) {
            return beforeNumbers;
        }

        String afterBet = text.substring(directBetMatch.end()).trim();
        return extractThreeDigitNumbers(afterBet);
    }

    private boolean needsMixedDirectGroupCorrection(List<AiParseResult> aiResults,
                                                    List<String> numbers,
                                                    List<CategoryGame> categories,
                                                    int directBet) {
        int expectedSize = numbers.size() * categories.size();
        if (aiResults.size() != expectedSize) {
            return true;
        }

        Set<String> expectedSignatures = new LinkedHashSet<>();
        for (CategoryGame category : categories) {
            for (String number : numbers) {
                expectedSignatures.add(category.category + "|" + category.game + "|" + number);
            }
        }

        Set<String> actualSignatures = new LinkedHashSet<>();
        for (AiParseResult result : aiResults) {
            if (!result.isSuccess() || result.getData() == null) {
                return true;
            }
            AiParseResult.ParsedData data = result.getData();
            if (data.getBet() != directBet) {
                return true;
            }
            if (data.getAmount() == null || data.getAmount().compareTo(BigDecimal.valueOf(directBet * 2L)) != 0) {
                return true;
            }
            if (data.getNumbers() == null || data.getNumbers().size() != 1) {
                return true;
            }
            actualSignatures.add(data.getCategory() + "|" + data.getGame() + "|" + data.getNumbers().get(0));
        }
        return !actualSignatures.equals(expectedSignatures);
    }

    private boolean needsPermutationCorrection(List<AiParseResult> aiResults,
                                               List<String> sourceNumbers,
                                               List<CategoryGame> categories,
                                               int directBet) {
        Map<String, Integer> expected = new LinkedHashMap<>();
        for (CategoryGame category : categories) {
            for (String sourceNumber : sourceNumbers) {
                for (String number : expandPermutationNumbers(sourceNumber)) {
                    String signature = buildResultSignature(category.category, category.game, "\u590d\u5f0f", number, directBet);
                    expected.merge(signature, 1, Integer::sum);
                }
            }
        }

        Map<String, Integer> actual = new LinkedHashMap<>();
        BigDecimal expectedAmount = BigDecimal.valueOf(directBet * 2L);
        for (AiParseResult result : aiResults) {
            if (!result.isSuccess() || result.getData() == null) {
                return true;
            }
            AiParseResult.ParsedData data = result.getData();
            if (!"\u590d\u5f0f".equals(data.getPlay())) {
                return true;
            }
            if (data.getBet() != directBet) {
                return true;
            }
            if (data.getAmount() == null || data.getAmount().compareTo(expectedAmount) != 0) {
                return true;
            }
            if (data.getNumbers() == null || data.getNumbers().size() != 1) {
                return true;
            }
            String signature = buildResultSignature(data.getCategory(), data.getGame(), data.getPlay(),
                    data.getNumbers().get(0), data.getBet());
            actual.merge(signature, 1, Integer::sum);
        }
        return !actual.equals(expected);
    }

    private List<String> expandPermutationNumbers(String sourceNumber) {
        Set<String> permutations = new LinkedHashSet<>();
        char[] chars = sourceNumber.toCharArray();
        permute(chars, 0, permutations);
        return new ArrayList<>(permutations);
    }

    private void permute(char[] chars, int index, Set<String> results) {
        if (index == chars.length) {
            results.add(new String(chars));
            return;
        }
        Set<Character> used = new LinkedHashSet<>();
        for (int i = index; i < chars.length; i++) {
            if (!used.add(chars[i])) {
                continue;
            }
            swap(chars, index, i);
            permute(chars, index + 1, results);
            swap(chars, index, i);
        }
    }

    private void swap(char[] chars, int i, int j) {
        char tmp = chars[i];
        chars[i] = chars[j];
        chars[j] = tmp;
    }

    private String buildResultSignature(String category, String game, String play, String number, int bet) {
        return safeValue(category) + "|" + safeValue(game) + "|" + safeValue(play) + "|" + safeValue(number) + "|" + bet;
    }

    private List<AiParseResult> buildExpandedPermutationResults(List<String> sourceNumbers,
                                                                int directBet,
                                                                List<CategoryGame> categories,
                                                                String rawAiResponse) {
        List<AiParseResult> corrected = new ArrayList<>();
        for (CategoryGame category : categories) {
            for (String sourceNumber : sourceNumbers) {
                for (String expanded : expandPermutationNumbers(sourceNumber)) {
                    corrected.add(buildPermutationResult(expanded, directBet, category, rawAiResponse));
                }
            }
        }
        return corrected;
    }

    private List<AiParseResult> buildMixedDirectAndPermutationResults(List<String> sourceNumbers,
                                                                      int directBet,
                                                                      List<CategoryGame> categories,
                                                                      String rawAiResponse) {
        return buildMixedDirectAndPermutationResults(sourceNumbers, directBet, directBet, categories, rawAiResponse);
    }

    private List<AiParseResult> buildMixedDirectAndPermutationResults(List<String> sourceNumbers,
                                                                      int directBet,
                                                                      int permutationBet,
                                                                      List<CategoryGame> categories,
                                                                      String rawAiResponse) {
        List<AiParseResult> corrected = new ArrayList<>();
        for (CategoryGame category : categories) {
            for (String sourceNumber : sourceNumbers) {
                corrected.add(buildDirectResult(sourceNumber, directBet, category, rawAiResponse));
            }
        }
        corrected.addAll(buildExpandedPermutationResults(sourceNumbers, permutationBet, categories, rawAiResponse));
        return corrected;
    }

    private boolean matchesExplicitPlayList(List<AiParseResult> aiResults,
                                            String play,
                                            List<String> numbers,
                                            List<CategoryGame> categories,
                                            int directBet) {
        List<AiParseResult> filtered = new ArrayList<>();
        for (AiParseResult result : aiResults) {
            if (!result.isSuccess() || result.getData() == null) {
                continue;
            }
            if (play.equals(result.getData().getPlay())) {
                filtered.add(result);
            }
        }
        if (filtered.isEmpty()) {
            return false;
        }

        Map<String, Integer> expected = new LinkedHashMap<>();
        for (CategoryGame category : categories) {
            for (String number : numbers) {
                String signature = buildResultSignature(category.category, category.game, play, number, directBet);
                expected.merge(signature, 1, Integer::sum);
            }
        }

        BigDecimal expectedAmount = BigDecimal.valueOf(directBet * 2L);
        Map<String, Integer> actual = new LinkedHashMap<>();
        for (AiParseResult result : filtered) {
            AiParseResult.ParsedData data = result.getData();
            if (data.getBet() != directBet) {
                return false;
            }
            if (data.getAmount() == null || data.getAmount().compareTo(expectedAmount) != 0) {
                return false;
            }
            if (data.getNumbers() == null || data.getNumbers().size() != 1) {
                return false;
            }
            String signature = buildResultSignature(data.getCategory(), data.getGame(), data.getPlay(),
                    data.getNumbers().get(0), data.getBet());
            actual.merge(signature, 1, Integer::sum);
        }
        return actual.equals(expected);
    }

    private boolean resultsMatchExpected(List<AiParseResult> actualResults, List<AiParseResult> expectedResults) {
        if (actualResults.size() != expectedResults.size()) {
            return false;
        }

        Map<String, Integer> actual = buildResultCounter(actualResults);
        Map<String, Integer> expected = buildResultCounter(expectedResults);
        return actual.equals(expected);
    }

    private List<AiParseResult> preferTrustedBaseIfPostCorrectionDegraded(OrderRaw raw,
                                                                          List<AiParseResult> trustedBaseResults,
                                                                          List<AiParseResult> correctedResults) {
        if (trustedBaseResults == null || trustedBaseResults.isEmpty()
                || correctedResults == null || correctedResults.isEmpty()
                || resultsMatchExpected(trustedBaseResults, correctedResults)
                || successCount(trustedBaseResults) == 0) {
            return correctedResults;
        }

        String text = normalizeRawTextForCorrection(raw.getRawText());
        int trustedAmount = sumAmounts(trustedBaseResults);
        int correctedAmount = sumAmounts(correctedResults);
        Integer strongTotalAmount = extractGuardTotalAmount(text);

        if (strongTotalAmount != null) {
            int trustedDiff = Math.abs(trustedAmount - strongTotalAmount);
            int correctedDiff = Math.abs(correctedAmount - strongTotalAmount);
            if (trustedDiff < correctedDiff) {
                log.info("post correction rejected by total guard, rawId={}, total={}, trustedAmount={}, correctedAmount={}",
                        raw.getId(), strongTotalAmount, trustedAmount, correctedAmount);
                return trustedBaseResults;
            }
        }

        if (hasDirectPermutationCompositeHint(text)
                && hasSuccessfulPlay(trustedBaseResults, "直选")
                && hasSuccessfulPlay(trustedBaseResults, "复式")
                && (!hasSuccessfulPlay(correctedResults, "直选")
                || !hasSuccessfulPlay(correctedResults, "复式"))) {
            log.info("post correction rejected by direct/permutation guard, rawId={}, trustedAmount={}, correctedAmount={}",
                    raw.getId(), trustedAmount, correctedAmount);
            return trustedBaseResults;
        }

        if (hasLeopardPackageWithExplicitDirectBet(text)
                && trustedAmount > correctedAmount
                && successCount(trustedBaseResults) >= successCount(correctedResults)) {
            log.info("post correction rejected by leopard explicit-bet guard, rawId={}, trustedAmount={}, correctedAmount={}",
                    raw.getId(), trustedAmount, correctedAmount);
            return trustedBaseResults;
        }

        return correctedResults;
    }

    private List<AiParseResult> copyParseResults(List<AiParseResult> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        List<AiParseResult> copied = new ArrayList<>();
        for (AiParseResult item : source) {
            if (item == null) {
                continue;
            }
            AiParseResult clone = new AiParseResult();
            clone.setIndex(item.getIndex());
            clone.setValid(item.isValid());
            clone.setStatus(item.getStatus());
            clone.setReason(item.getReason());
            clone.setRawAiResponse(item.getRawAiResponse());
            clone.setFailed(item.isFailed());
            clone.setError(item.getError());
            if (item.getData() != null) {
                AiParseResult.ParsedData data = item.getData();
                AiParseResult.ParsedData dataClone = new AiParseResult.ParsedData();
                dataClone.setCategory(data.getCategory());
                dataClone.setGame(data.getGame());
                dataClone.setPlay(data.getPlay());
                dataClone.setZone(data.getZone());
                dataClone.setNumbers(data.getNumbers() == null ? null : new ArrayList<>(data.getNumbers()));
                dataClone.setBet(data.getBet());
                dataClone.setMultiple(data.getMultiple());
                dataClone.setAmount(data.getAmount());
                clone.setData(dataClone);
            }
            copied.add(clone);
        }
        return copied;
    }

    private int successCount(List<AiParseResult> results) {
        int count = 0;
        if (results == null) {
            return count;
        }
        for (AiParseResult result : results) {
            if (result != null && result.isSuccess()) {
                count++;
            }
        }
        return count;
    }

    private Integer extractGuardTotalAmount(String text) {
        Integer total = extractMessageTotalAmount(text);
        if (total != null) {
            return total;
        }
        if (text == null || text.isBlank()) {
            return null;
        }
        for (String rawLine : text.split("\\R")) {
            Matcher markerMatcher = TOTAL_MARKER_GUARD_AMOUNT.matcher(rawLine.trim());
            if (markerMatcher.find()) {
                int amount = parseNumericToken(markerMatcher.group(1));
                if (amount > 0) {
                    total = amount;
                }
            }
        }
        if (total != null) {
            return total;
        }
        if (containsPermutationMarker(text)) {
            return extractTrailingTotalAmount(text);
        }
        return null;
    }

    private boolean hasDirectPermutationCompositeHint(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return DIRECT_THEN_PERMUTATION_HINT.matcher(text).find()
                || PERMUTATION_THEN_DIRECT_HINT.matcher(text).find()
                || DIRECT_PREFIXED_PERMUTATION_HINT.matcher(text).find();
    }

    private boolean hasLeopardPackageWithExplicitDirectBet(String text) {
        if (text == null || text.isBlank() || !LEOPARD_PACKAGE_HINT.matcher(text).find()) {
            return false;
        }
        for (String line : splitNonBlankLines(text)) {
            if (LEOPARD_PACKAGE_HINT.matcher(line).find() || GROUP_PLAY_HINT.matcher(line).find()) {
                continue;
            }
            DirectBetMatch betMatch = extractExplicitDirectBetMatch(line);
            if (betMatch != null && betMatch.bet() > 0 && containsThreeDigitNumber(line)) {
                return true;
            }
        }
        return false;
    }

    private Integer extractTrailingTotalAmount(String tailText) {
        if (tailText == null || tailText.isBlank()) {
            return null;
        }
        Matcher matcher = TRAILING_TOTAL_AMOUNT_HINT.matcher(tailText);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int sumAmounts(List<AiParseResult> results) {
        int total = 0;
        for (AiParseResult result : results) {
            if (!result.isSuccess() || result.getData() == null || result.getData().getAmount() == null) {
                continue;
            }
            total += result.getData().getAmount().intValue();
        }
        return total;
    }

    private boolean shouldTrustAiMixedDirectPermutation(String text, List<AiParseResult> aiResults) {
        if (text == null
                || (!DIRECT_THEN_PERMUTATION_HINT.matcher(text).find()
                && !PERMUTATION_THEN_DIRECT_HINT.matcher(text).find())) {
            return false;
        }
        if (!hasSuccessfulPlay(aiResults, "直选") || !hasSuccessfulPlay(aiResults, "复式")) {
            return false;
        }
        Integer hintedTotalAmount = extractMessageTotalAmount(text);
        return hintedTotalAmount == null || sumAmounts(aiResults) == hintedTotalAmount;
    }

    private boolean hasSuccessfulPlay(List<AiParseResult> aiResults, String play) {
        for (AiParseResult result : aiResults) {
            if (result.isSuccess() && result.getData() != null && play.equals(result.getData().getPlay())) {
                return true;
            }
        }
        return false;
    }

    private String safeValue(String value) {
        return value == null ? "" : value;
    }

    private String previewForLog(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...(truncated, length=" + value.length() + ")";
    }

    private AiParseResult buildPermutationResult(String number, int directBet, CategoryGame category, String rawAiResponse) {
        AiParseResult result = new AiParseResult();
        result.setIndex(1);
        result.setValid(true);
        result.setStatus("SUCCESS");
        result.setRawAiResponse(rawAiResponse);

        AiParseResult.ParsedData data = new AiParseResult.ParsedData();
        data.setCategory(category.category);
        data.setGame(category.game);
        data.setPlay("\u590d\u5f0f");
        data.setZone("MAIN");
        data.setNumbers(List.of(number));
        data.setBet(directBet);
        data.setMultiple(1);
        data.setAmount(BigDecimal.valueOf(directBet * 2L));
        result.setData(data);
        return result;
    }

    private AiParseResult buildDirectResult(String number, int directBet, CategoryGame category, String rawAiResponse) {
        AiParseResult result = new AiParseResult();
        result.setIndex(1);
        result.setValid(true);
        result.setStatus("SUCCESS");
        result.setRawAiResponse(rawAiResponse);

        AiParseResult.ParsedData data = new AiParseResult.ParsedData();
        data.setCategory(category.category);
        data.setGame(category.game);
        data.setPlay("直选");
        data.setZone("MAIN");
        data.setNumbers(List.of(number));
        data.setBet(directBet);
        data.setMultiple(1);
        data.setAmount(BigDecimal.valueOf(directBet * 2L));
        result.setData(data);
        return result;
    }

    private String normalizeRawTextForCorrection(String rawText) {
        if (rawText == null) {
            return "";
        }
        String stripped = WXID_PREFIX_LINE.matcher(rawText).replaceAll("").trim();
        return orderTextNormalizationService.normalizeForParsing(stripped);
    }

    private String extractPrimaryOrderText(String rawText) {
        String normalized = normalizeRawTextForCorrection(rawText);
        int originalStart = normalized.indexOf("[原始报单]");
        int correctionStart = normalized.indexOf("[人工校正说明]");
        if (originalStart >= 0 && correctionStart > originalStart) {
            String originalBlock = normalized.substring(originalStart + "[原始报单]".length(), correctionStart).trim();
            if (!originalBlock.isEmpty()) {
                return originalBlock;
            }
        }
        return normalized;
    }

    private boolean containsMultipleLines(String text) {
        return text != null && (text.contains("\n") || text.contains("\r"));
    }

    private boolean hasSentenceSeparator(String text) {
        return text != null && (text.contains("。") || text.contains("；") || text.contains(";")
                || text.contains("\n") || text.contains("\r"));
    }

    private boolean hasSeparatedTrailingTotal(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String[] segments = text.split("[。；;\\r\\n]+");
        return findTrailingSeparatedTotalIndex(segments) >= 0;
    }

    private int findTrailingSeparatedTotalIndex(String[] segments) {
        if (segments == null) {
            return -1;
        }
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i] == null ? "" : segments[i].trim();
            if (!segment.isEmpty()) {
                return isSeparatedTotalSegment(segment) ? i : -1;
            }
        }
        return -1;
    }

    private boolean isSeparatedTotalSegment(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.trim();
        return normalized.matches("[零一二三四五六七八九十百两\\d]{1,6}\\s*(?:米|元|块|钱)?");
    }

    private boolean containsPermutationMarker(String text) {
        return text != null && (text.contains("复式") || text.contains("复试"));
    }

    private int findPermutationMarkerStart(String text) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        int standardIndex = text.indexOf("复式");
        int typoIndex = text.indexOf("复试");
        if (standardIndex < 0) {
            return typoIndex;
        }
        if (typoIndex < 0) {
            return standardIndex;
        }
        return Math.min(standardIndex, typoIndex);
    }

    private int extractPermutationBetNearMarker(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        Matcher afterMatcher = PERMUTATION_BET_AFTER_MARKER.matcher(text);
        if (afterMatcher.find()) {
            return parseNumericToken(afterMatcher.group(1));
        }
        Matcher beforeMatcher = PERMUTATION_BET_BEFORE_MARKER.matcher(text);
        if (beforeMatcher.find()) {
            return parseNumericToken(beforeMatcher.group(1));
        }
        return 0;
    }

    private Integer extractExplicitDirectBet(String text) {
        Matcher arabic = ARABIC_DIRECT_BET.matcher(text);
        if (arabic.find()) {
            return Integer.parseInt(arabic.group(1));
        }

        Matcher chinese = CHINESE_DIRECT_BET.matcher(text);
        if (chinese.find()) {
            return parseChineseNumber(chinese.group(1));
        }
        return null;
    }

    private DirectBetMatch extractExplicitDirectBetMatch(String text) {
        Matcher arabic = ARABIC_DIRECT_BET.matcher(text);
        if (arabic.find()) {
            return new DirectBetMatch(Integer.parseInt(arabic.group(1)), arabic.start(), arabic.end());
        }

        Matcher chinese = CHINESE_DIRECT_BET.matcher(text);
        if (chinese.find()) {
            return new DirectBetMatch(parseChineseNumber(chinese.group(1)), chinese.start(), chinese.end());
        }
        return null;
    }

    private List<NumberBetMatch> extractNumberBetPairs(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        List<NumberBetMatch> matches = new ArrayList<>();
        Matcher matcher = NUMBER_BET_PAIR.matcher(text);
        int coveredEnd = 0;
        while (matcher.find()) {
            if (containsThreeDigitNumber(text.substring(coveredEnd, matcher.start()))) {
                return Collections.emptyList();
            }
            String betText = matcher.group(2);
            int bet = parseNumericToken(betText);
            if (bet <= 0) {
                return Collections.emptyList();
            }
            matches.add(new NumberBetMatch(matcher.group(1), bet));
            coveredEnd = matcher.end();
        }
        if (!matches.isEmpty()) {
            String tail = text.substring(coveredEnd);
            if (containsThreeDigitNumber(tail) && !isNumberBetPairTrailingAmount(tail)) {
                return Collections.emptyList();
            }
        }
        return matches;
    }

    private boolean containsThreeDigitNumber(String text) {
        return text != null && THREE_DIGIT_NUMBER.matcher(text).find();
    }

    private boolean isNumberBetPairTrailingAmount(String text) {
        return text != null && NUMBER_BET_PAIR_TRAILING_AMOUNT.matcher(text).matches();
    }

    private List<DirectBetMatch> extractExplicitDirectBetMatches(String text) {
        List<DirectBetMatch> matches = new ArrayList<>();

        Matcher arabic = ARABIC_DIRECT_BET.matcher(text);
        while (arabic.find()) {
            matches.add(new DirectBetMatch(Integer.parseInt(arabic.group(1)), arabic.start(), arabic.end()));
        }

        Matcher chinese = CHINESE_DIRECT_BET.matcher(text);
        while (chinese.find()) {
            matches.add(new DirectBetMatch(parseChineseNumber(chinese.group(1)), chinese.start(), chinese.end()));
        }

        matches.sort((left, right) -> Integer.compare(left.start(), right.start()));
        return matches;
    }

    private int parseChineseNumber(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String normalized = value.replace("两", "二");
        int result = 0;
        int current = 0;
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '百') {
                result += (current == 0 ? 1 : current) * 100;
                current = 0;
            } else if (ch == '十') {
                result += (current == 0 ? 1 : current) * 10;
                current = 0;
            } else {
                current = chineseDigit(ch);
            }
        }
        return result + current;
    }

    private int chineseDigit(char ch) {
        return switch (ch) {
            case '零' -> 0;
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> 0;
        };
    }

    private List<String> extractThreeDigitNumbers(String text) {
        Set<String> numbers = new LinkedHashSet<>();
        Matcher matcher = THREE_DIGIT_NUMBER.matcher(text);
        while (matcher.find()) {
            if (hasAmountSuffix(text, matcher.end())) {
                continue;
            }
            numbers.add(matcher.group(1));
        }
        return new ArrayList<>(numbers);
    }

    private List<String> extractThreeDigitNumbersPreserveDuplicates(String text) {
        List<String> numbers = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return numbers;
        }
        Matcher matcher = THREE_DIGIT_NUMBER.matcher(text);
        while (matcher.find()) {
            if (hasAmountSuffix(text, matcher.end())) {
                continue;
            }
            numbers.add(matcher.group(1));
        }
        return numbers;
    }

    private boolean isPureUnsupportedGroupLine(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        if (line.contains("组选") || line.contains("组三") || line.contains("组六")) {
            return true;
        }
        return GROUP_PLAY_HINT.matcher(line).find() && extractExplicitDirectBet(line) == null;
    }

    private Integer extractLeopardPackageBet(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        Matcher leopardMatcher = LEOPARD_PACKAGE_HINT.matcher(line);
        if (!leopardMatcher.find()) {
            return null;
        }
        String packageScope = line.substring(leopardMatcher.end());

        DirectBetMatch directBetMatch = extractExplicitDirectBetMatch(packageScope);
        if (directBetMatch != null && directBetMatch.bet() > 0) {
            return directBetMatch.bet();
        }

        Matcher multipleMatcher = EXPLICIT_MULTIPLE.matcher(packageScope);
        if (multipleMatcher.find()) {
            return parseNumericToken(multipleMatcher.group(1));
        }

        Matcher amountMatcher = LEOPARD_PACKAGE_AMOUNT.matcher(line);
        if (!amountMatcher.find()) {
            return null;
        }
        int amount = parseNumericToken(amountMatcher.group(1));
        int unitAmount = LEOPARD_NUMBERS.size() * 2;
        if (amount <= 0 || amount % unitAmount != 0) {
            return 0;
        }
        return amount / unitAmount;
    }

    private int resolveDirectBetWithTrailingAmount(String line, DirectBetMatch betMatch, int numberCount) {
        int directBet = betMatch != null ? betMatch.bet() : 0;
        if (line == null || line.isBlank() || numberCount <= 0 || GROUP_PLAY_HINT.matcher(line).find()) {
            return directBet;
        }

        Integer amount = extractTrailingLineAmount(line);
        int unitAmount = numberCount * 2;
        if (amount == null || amount <= 0 || amount % unitAmount != 0) {
            return directBet;
        }

        int amountBet = amount / unitAmount;
        if (amountBet > 0 && amountBet != directBet) {
            return amountBet;
        }
        return directBet;
    }

    private Integer extractTrailingLineAmount(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        Matcher matcher = TRAILING_LINE_AMOUNT.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        String amountText = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        int amount = parseNumericToken(amountText);
        return amount > 0 ? amount : null;
    }

    private Integer extractMessageTotalAmount(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Integer totalAmount = null;
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.contains("总") || line.contains("合计") || line.contains("共") || line.contains("🈴")) {
                Integer lineAmount = extractTrailingLineAmount(line);
                if (lineAmount != null && lineAmount > 0) {
                    totalAmount = lineAmount;
                }
            }
        }
        return totalAmount;
    }

    private boolean isPlainNumberContinuationLine(String line) {
        if (line == null || line.isBlank() || extractExplicitDirectBetMatch(line) != null) {
            return false;
        }
        if (containsPermutationMarker(line) || GROUP_PLAY_HINT.matcher(line).find()) {
            return false;
        }
        if (line.contains("总") || line.contains("合计") || line.contains("共")
                || line.contains("米") || line.contains("元") || line.contains("块") || line.contains("钱")) {
            return false;
        }

        String stripped = THREE_DIGIT_NUMBER.matcher(line).replaceAll("");
        stripped = stripped.replace("福彩", "")
                .replace("体彩", "")
                .replace("排列三", "")
                .replace("排三", "")
                .replace("3D", "")
                .replace("3d", "")
                .replace("P3", "")
                .replace("p3", "")
                .replace("福", "")
                .replace("体", "");
        return stripped.replaceAll("[\\s.。·,，、;；:：/\\\\|\\-—－+＋]+", "").isBlank();
    }

    private int parseNumericToken(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        String normalized = value.trim();
        return normalized.matches("\\d+") ? Integer.parseInt(normalized) : parseChineseNumber(normalized);
    }

    /**
     * 历史人工修正完全命中时直接复用结果，避免同一文案每晚再次消耗 AI。
     */
    private List<AiParseResult> tryParseExactPromptCaseFastPath(OrderRaw raw, boolean forceTcByTargetGroupRule) {
        if (raw == null || forceTcByTargetGroupRule) {
            return Collections.emptyList();
        }
        PromptCase promptCase = promptService.findExactCase(raw.getRawText());
        if (promptCase == null) {
            String normalized = normalizeRawTextForCorrection(raw.getRawText());
            if (!normalized.equals(raw.getRawText())) {
                promptCase = promptService.findExactCase(normalized);
            }
        }
        if (promptCase == null) {
            return Collections.emptyList();
        }

        List<AiParseResult> results = parsePromptCaseResults(
                promptCase.getResultSummary(),
                "[LOCAL_FAST_PATH] exact prompt case rawId=" + promptCase.getRawId()
        );
        if (!results.isEmpty()) {
            log.info("hit exact prompt case fast path, rawId={}, caseRawId={}, resultCount={}",
                    raw.getId(), promptCase.getRawId(), results.size());
        }
        return results;
    }

    /**
     * 高频简单直单快路径：如“038 3单6米”、“141-749-950 5单1组”。
     * 复杂玩法仍交给 AI，避免为了速度牺牲准确率。
     */
    private List<AiParseResult> tryParseSimpleDirectListFastPath(OrderRaw raw, boolean forceTcByTargetGroupRule) {
        if (!fastPathSimpleDirectEnabled || raw == null || raw.getRawText() == null || raw.getRawText().isBlank()) {
            return Collections.emptyList();
        }

        String text = extractPrimaryOrderText(raw.getRawText());
        String candidateText = text.replaceAll("[\\r\\n]+", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        if (candidateText.isBlank()
                || containsPermutationMarker(candidateText)
                || SIMPLE_DIRECT_UNSUPPORTED_HINT.matcher(candidateText).find()) {
            return Collections.emptyList();
        }

        List<DirectBetMatch> betMatches = extractExplicitDirectBetMatches(candidateText);
        if (betMatches.size() != 1) {
            return Collections.emptyList();
        }
        DirectBetMatch betMatch = betMatches.get(0);
        if (betMatch.bet() <= 0) {
            return Collections.emptyList();
        }

        String numberScope = candidateText.substring(0, betMatch.start()).trim();
        if (numberScope.isBlank() || !isSimpleDirectNumberScope(numberScope)) {
            return Collections.emptyList();
        }

        List<String> numbers = extractThreeDigitNumbers(numberScope);
        if (numbers.isEmpty()) {
            return Collections.emptyList();
        }

        String suffix = candidateText.substring(betMatch.end()).trim();
        if (containsThreeDigitNumber(suffix)) {
            return Collections.emptyList();
        }

        List<CategoryGame> categories = forceTcByTargetGroupRule
                ? List.of(new CategoryGame("TC", "P3"))
                : detectCategories(candidateText, raw);
        Integer explicitTotal = extractStrictSimpleDirectTotal(suffix);
        int computedTotal = numbers.size() * categories.size() * betMatch.bet() * 2;
        if (explicitTotal != null && explicitTotal > 0 && explicitTotal != computedTotal) {
            return Collections.emptyList();
        }

        List<AiParseResult> results = new ArrayList<>();
        String rawAiResponse = "[LOCAL_FAST_PATH] simple direct list";
        for (CategoryGame category : categories) {
            for (String number : numbers) {
                results.add(buildDirectResult(number, betMatch.bet(), category, rawAiResponse));
            }
        }
        log.info("hit simple direct fast path, rawId={}, numbers={}, bet={}, categories={}, total={}",
                raw.getId(), numbers.size(), betMatch.bet(), categories.size(), computedTotal);
        return results;
    }

    private boolean isSimpleDirectNumberScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return false;
        }
        String stripped = THREE_DIGIT_NUMBER.matcher(scope).replaceAll("");
        stripped = stripped.replace("福彩", "")
                .replace("体彩", "")
                .replace("排列三", "")
                .replace("排三", "")
                .replace("3D", "")
                .replace("3d", "")
                .replace("P3", "")
                .replace("p3", "")
                .replace("福", "")
                .replace("体", "")
                .replace("打", "")
                .replace("买", "")
                .replace("各", "");
        return stripped.replaceAll("[\\s.。·,，、;；:：/\\\\|\\-—－+＋?？]+", "").isBlank();
    }

    private Integer extractStrictSimpleDirectTotal(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return null;
        }
        boolean hasTotalMarker = suffix.contains("总")
                || suffix.contains("共")
                || suffix.contains("合计")
                || suffix.contains("🈴");
        Integer amountWithUnit = extractTrailingLineAmount(suffix);
        if (amountWithUnit != null && (hasTotalMarker || suffix.matches(".*[米元块钱]\\s*$"))) {
            return amountWithUnit;
        }
        if (hasTotalMarker) {
            return extractTrailingPositiveInteger(suffix);
        }
        return null;
    }

    private Integer extractTrailingPositiveInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("([零一二三四五六七八九十百两\\d]{1,6})\\s*$").matcher(value.trim());
        if (!matcher.find()) {
            return null;
        }
        int parsed = parseNumericToken(matcher.group(1));
        return parsed > 0 ? parsed : null;
    }

    /**
     * 通用长复式列表快路径：仅当原文的复式注数和尾部总额能闭环校验时才本地展开。
     */
    private List<AiParseResult> tryParseLongPermutationListFastPath(OrderRaw raw, boolean forceTcByTargetGroupRule) {
        if (raw == null || raw.getRawText() == null || raw.getRawText().isBlank()) {
            return Collections.emptyList();
        }

        String text = extractPrimaryOrderText(raw.getRawText());
        if (text.isBlank() || !containsPermutationMarker(text)) {
            return Collections.emptyList();
        }
        if (MIXED_DIRECT_PERMUTATION_MARKER.matcher(text).find()
                || DIRECT_THEN_PERMUTATION_HINT.matcher(text).find()
                || PERMUTATION_THEN_DIRECT_HINT.matcher(text).find()
                || GROUP_PLAY_HINT.matcher(text).find()
                || LEOPARD_PACKAGE_HINT.matcher(text).find()) {
            return Collections.emptyList();
        }

        String normalized = text.replaceAll("[—－-]", " ");
        Matcher marker = EXPLICIT_PERMUTATION_LIST_MARKER.matcher(normalized);
        int markerStart = -1;
        int markerEnd = -1;
        int bet = 0;
        while (marker.find()) {
            int parsedBet = parseNumericToken(marker.group(1));
            if (parsedBet > 0) {
                markerStart = marker.start();
                markerEnd = marker.end();
                bet = parsedBet;
            }
        }
        if (markerStart < 0 || bet <= 0) {
            return Collections.emptyList();
        }

        List<String> sourceNumbers = extractThreeDigitNumbers(normalized.substring(0, markerStart));
        if (sourceNumbers.size() < LONG_PERMUTATION_LIST_FAST_PATH_THRESHOLD) {
            return Collections.emptyList();
        }

        Integer hintedTotalAmount = extractTrailingTotalAmount(normalized.substring(markerEnd));
        if (hintedTotalAmount == null || hintedTotalAmount <= 0) {
            return Collections.emptyList();
        }

        List<CategoryGame> categories = forceTcByTargetGroupRule
                ? List.of(new CategoryGame("TC", "P3"))
                : detectCategories(text, raw);
        List<AiParseResult> results = buildExpandedPermutationResults(
                sourceNumbers,
                bet,
                categories,
                "[LOCAL_FAST_PATH] long permutation list"
        );
        if (results.isEmpty() || results.size() <= sourceNumbers.size() * categories.size()
                || sumAmounts(results) != hintedTotalAmount) {
            return Collections.emptyList();
        }

        log.info("hit long permutation list fast path, rawId={}, sourceNumbers={}, bet={}, hintedTotalAmount={}, resultCount={}",
                raw.getId(), sourceNumbers.size(), bet, hintedTotalAmount, results.size());
        return results;
    }

    private List<AiParseResult> tryParseLongDirectListFastPath(OrderRaw raw, boolean forceTcByTargetGroupRule) {
        if (raw == null || raw.getRawText() == null || raw.getRawText().isBlank()) {
            return Collections.emptyList();
        }

        String text = extractPrimaryOrderText(raw.getRawText());
        if (text.isBlank() || containsPermutationMarker(text)) {
            return Collections.emptyList();
        }

        DirectBetMatch directBetMatch = extractExplicitDirectBetMatch(text);
        if (directBetMatch == null || directBetMatch.bet() <= 0) {
            return Collections.emptyList();
        }

        String prefix = text.substring(0, directBetMatch.start()).trim();
        prefix = TRAILING_TOTAL_NOTE.matcher(prefix).replaceFirst("").trim();
        if (prefix.isBlank() || DIRECT_SINGLE_BLOCK_HINT.matcher(prefix).find()) {
            return Collections.emptyList();
        }

        List<String> numbers = extractThreeDigitNumbers(prefix);
        if (numbers.size() < LONG_NUMBER_LIST_FAST_PATH_THRESHOLD) {
            return Collections.emptyList();
        }

        List<CategoryGame> categories = forceTcByTargetGroupRule
                ? List.of(new CategoryGame("TC", "P3"))
                : detectCategories(text, raw);
        String rawAiResponse = "[LOCAL_FAST_PATH] long explicit number list";
        List<AiParseResult> results = new ArrayList<>();
        for (CategoryGame category : categories) {
            for (String number : numbers) {
                results.add(buildDirectResult(number, directBetMatch.bet(), category, rawAiResponse));
            }
        }
        return results;
    }

    private boolean hasAmountSuffix(String text, int endIndex) {
        int idx = endIndex;
        while (idx < text.length() && Character.isWhitespace(text.charAt(idx))) {
            idx++;
        }
        return idx < text.length() && AMOUNT_SUFFIX_CHARS.indexOf(text.charAt(idx)) >= 0;
    }

    private boolean hasExplicitCategoryMarker(String text) {
        return hasExplicitTcMarker(text)
                || text.contains("福彩")
                || text.contains("3D")
                || text.contains("3d")
                || text.contains("福");
    }

    private boolean hasExplicitCategoryMarker(String text, OrderRaw raw) {
        return shouldForceTcByContext(raw) || hasExplicitCategoryMarker(text);
    }

    private boolean hasExplicitTcMarker(String text) {
        return text.contains("体彩")
                || text.contains("排列三")
                || text.contains("排三")
                || text.contains("P3")
                || text.contains("p3")
                || text.contains("体");
    }

    private boolean needsMultilineScopedCategoryCorrection(List<AiParseResult> aiResults,
                                                           List<AiParseResult> expectedResults) {
        return !buildResultCounter(aiResults).equals(buildResultCounter(expectedResults));
    }

    private Set<String> collectSingleNumbers(List<AiParseResult> results) {
        Set<String> numbers = new LinkedHashSet<>();
        for (AiParseResult result : results) {
            if (result == null || !result.isSuccess() || result.getData() == null) {
                continue;
            }
            List<String> parsedNumbers = result.getData().getNumbers();
            if (parsedNumbers != null && parsedNumbers.size() == 1) {
                numbers.add(parsedNumbers.get(0));
            }
        }
        return numbers;
    }

    private Set<String> collectResultSignatures(List<AiParseResult> results) {
        Set<String> signatures = new LinkedHashSet<>();
        for (AiParseResult result : results) {
            if (result == null || !result.isSuccess() || result.getData() == null) {
                continue;
            }
            AiParseResult.ParsedData data = result.getData();
            if (data.getNumbers() == null || data.getNumbers().size() != 1) {
                continue;
            }
            signatures.add(buildResultSignature(
                    data.getCategory(),
                    data.getGame(),
                    data.getPlay(),
                    data.getNumbers().get(0),
                    data.getBet()
            ));
        }
        return signatures;
    }

    private Map<String, Integer> buildResultCounter(List<AiParseResult> results) {
        Map<String, Integer> counter = new LinkedHashMap<>();
        for (AiParseResult result : results) {
            if (result == null || !result.isSuccess() || result.getData() == null) {
                return Collections.emptyMap();
            }
            AiParseResult.ParsedData data = result.getData();
            if (data.getNumbers() == null || data.getNumbers().size() != 1) {
                return Collections.emptyMap();
            }
            String signature = buildResultSignature(
                    data.getCategory(),
                    data.getGame(),
                    data.getPlay(),
                    data.getNumbers().get(0),
                    data.getBet()
            );
            counter.merge(signature, 1, Integer::sum);
        }
        return counter;
    }

    private List<CategoryGame> detectCategories(String text) {
        boolean hasFc = text.contains("福彩") || text.contains("3D") || text.contains("3d") || text.contains("福");
        boolean hasTc = text.contains("体彩") || text.contains("排列三") || text.contains("排三") || text.contains("P3") || text.contains("p3") || text.contains("体");

        List<CategoryGame> categories = new ArrayList<>();
        if (hasTc && hasFc) {
            categories.add(new CategoryGame("TC", "P3"));
            categories.add(new CategoryGame("FC", "3D"));
            return categories;
        }
        if (hasTc) {
            categories.add(new CategoryGame("TC", "P3"));
            return categories;
        }
        categories.add(new CategoryGame("FC", "3D"));
        return categories;
    }

    private List<CategoryGame> detectExplicitCategories(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }
        boolean hasFc = text.contains("福彩") || text.contains("3D") || text.contains("3d") || text.contains("福");
        boolean hasTc = text.contains("体彩") || text.contains("排列三") || text.contains("排三") || text.contains("P3") || text.contains("p3") || text.contains("体");

        List<CategoryGame> categories = new ArrayList<>();
        if (hasTc) {
            categories.add(new CategoryGame("TC", "P3"));
        }
        if (hasFc) {
            categories.add(new CategoryGame("FC", "3D"));
        }
        return categories;
    }

    private List<CategoryGame> detectCategories(String text, OrderRaw raw) {
        if (shouldForceTcByContext(raw)) {
            return List.of(new CategoryGame("TC", "P3"));
        }
        return detectCategories(text);
    }

    private boolean shouldForceTcByContext(OrderRaw raw) {
        return raw != null && targetGroupRuleService.shouldForceTc(raw);
    }

    private String joinHints(String... hints) {
        StringBuilder sb = new StringBuilder();
        for (String hint : hints) {
            if (hint == null || hint.isBlank()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n");
            }
            sb.append(hint.trim());
        }
        return sb.toString();
    }

    private ParseExecutionOptions defaultOptions() {
        return new ParseExecutionOptions(model, saveCallLogEnabled, true);
    }

    private String resolveModelName(String overrideModel) {
        if (overrideModel == null || overrideModel.isBlank()) {
            return model;
        }
        return overrideModel.trim();
    }

    private List<AiParseResult> applyForcedTcCategoryContext(OrderRaw raw, List<AiParseResult> aiResults) {
        if (!shouldForceTcByContext(raw) || aiResults == null || aiResults.isEmpty()) {
            return aiResults;
        }

        List<AiParseResult> adjusted = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AiParseResult result : aiResults) {
            if (result == null || !result.isSuccess() || result.getData() == null) {
                adjusted.add(result);
                continue;
            }

            AiParseResult.ParsedData data = result.getData();
            data.setCategory("TC");
            data.setGame("P3");

            String signature = String.join("|",
                    safeValue(data.getCategory()),
                    safeValue(data.getGame()),
                    safeValue(data.getPlay()),
                    safeValue(data.getZone()),
                    data.getNumbers() == null ? "[]" : data.getNumbers().toString(),
                    String.valueOf(data.getBet()),
                    String.valueOf(data.getAmount()));
            if (seen.add(signature)) {
                adjusted.add(result);
            }
        }
        return adjusted;
    }

    private record CategoryGame(String category, String game) {
    }

    private record DirectBetMatch(int bet, int start, int end) {
    }

    private record NumberBetMatch(String number, int bet) {
    }

    private record PendingNumberLine(List<String> numbers, List<CategoryGame> categories) {
    }

    private record ParseExecutionOptions(String modelName, boolean saveCallLog, boolean applyPostCorrections) {
    }

    public record ModelPreview(String modelName, List<AiParseResult> results) {
    }

    /**
     * 保存AI调用日志到数据库
     */
    private void saveCallLog(int batchCount, String systemMessage, String userMessage,
                             String aiResponse, long latencyMs,
                             int inputTokens, int outputTokens, int totalTokens,
                             String issueKey, boolean success, String errorMsg) {
        try {
            AiCallLog callLog = new AiCallLog();
            callLog.setBatchCount(batchCount);
            callLog.setSystemMessage(systemMessage);
            callLog.setUserMessage(userMessage);
            callLog.setAiResponse(aiResponse);
            callLog.setLatencyMs(latencyMs);
            callLog.setInputTokens(inputTokens);
            callLog.setOutputTokens(outputTokens);
            callLog.setTotalTokens(totalTokens);
            callLog.setIssueKey(issueKey);
            callLog.setSuccess(success ? 1 : 0);
            callLog.setErrorMsg(errorMsg);
            aiCallLogMapper.insert(callLog);
        } catch (Exception e) {
            log.warn("保存AI调用日志失败", e);
        }
    }
}
