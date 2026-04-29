package cn.daenx.myadmin.modules.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 提示词加载服务
 * 支持从classpath或文件系统加载提示词模板，支持热加载
 */
@Slf4j
@Service
public class PromptService {

    private final PromptCaseService promptCaseService;

    @Value("${ai.prompt.path:classpath:prompts/order-parse.prompt}")
    private String promptPath;

    @Value("${ai.prompt.hot-reload:false}")
    private boolean hotReload;

    @Value("${ai.prompt.reload-interval:60000}")
    private long reloadInterval;

    private volatile String cachedTemplate;
    private volatile long lastLoadTime;

    public PromptService(PromptCaseService promptCaseService) {
        this.promptCaseService = promptCaseService;
    }

    /**
     * 获取提示词模板
     */
    public String getTemplate() {
        if (cachedTemplate == null || (hotReload && shouldReload())) {
            synchronized (this) {
                if (cachedTemplate == null || (hotReload && shouldReload())) {
                    cachedTemplate = loadTemplate();
                    lastLoadTime = System.currentTimeMillis();
                    log.info("提示词模板已加载: path={}", promptPath);
                }
            }
        }
        return cachedTemplate;
    }

    /**
     * 获取系统提示词（规则部分，不含消息列表）
     */
    public String getSystemPrompt() {
        return getTemplate()
                + promptCaseService.buildPromptPatchAppendix()
                + promptCaseService.buildPromptAppendix();
    }

    public String getSystemPrompt(String rawText) {
        return getTemplate()
                + promptCaseService.buildPromptPatchAppendix(rawText)
                + promptCaseService.buildPromptAppendix(rawText);
    }

    public String getUserHint(String rawText) {
        return promptCaseService.buildUserCaseHint(rawText);
    }

    public PromptCaseService.PromptCase findExactCase(String rawText) {
        return promptCaseService.findExactCase(rawText);
    }

    /**
     * 渲染提示词（替换变量）
     * @param variables 变量映射，如 {"MESSAGES": "消息内容"}
     * @return 渲染后的提示词
     */
    public String render(Map<String, String> variables) {
        String template = getTemplate();
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            template = template.replace("${" + entry.getKey() + "}", entry.getValue());
        }
        return template;
    }

    /**
     * 手动刷新缓存
     */
    public void refresh() {
        synchronized (this) {
            cachedTemplate = null;
            log.info("提示词缓存已清除，下次获取时将重新加载");
        }
    }

    /**
     * 判断是否需要重新加载
     */
    private boolean shouldReload() {
        return System.currentTimeMillis() - lastLoadTime > reloadInterval;
    }

    /**
     * 加载提示词模板
     */
    private String loadTemplate() {
        try {
            if (promptPath.startsWith("classpath:")) {
                // 从classpath加载
                String resourcePath = promptPath.substring("classpath:".length());
                ClassPathResource resource = new ClassPathResource(resourcePath);
                return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } else {
                // 从文件系统加载
                return Files.readString(Path.of(promptPath), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.error("加载提示词模板失败: path={}", promptPath, e);
            throw new RuntimeException("提示词模板加载失败: " + promptPath, e);
        }
    }
}
