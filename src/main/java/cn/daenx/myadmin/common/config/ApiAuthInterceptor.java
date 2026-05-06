package cn.daenx.myadmin.common.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

@Slf4j
@Component
public class ApiAuthInterceptor implements HandlerInterceptor {
    private static final Set<String> READ_ONLY_WHITELIST = Set.of(
            "/api/check-messages",
            "/api/stats",
            "/api/top-source-messages",
            "/api/message-count",
            "/api/raw-count",
            "/api/runtime-status"
    );

    @Value("${api.auth.key:}")
    private String apiKey;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String configuredKey = normalize(apiKey);
        String requestKey = normalize(request.getHeader("X-API-Key"));
        if (configuredKey != null) {
            if (configuredKey.equals(requestKey)) {
                return true;
            }
            reject(response, "Unauthorized");
            return false;
        }

        if (isSafeReadOnlyRequest(request) || isLocalRequest(request)) {
            return true;
        }

        log.warn("blocked api request because API_AUTH_KEY is not configured, method={}, path={}",
                request.getMethod(), request.getRequestURI());
        reject(response, "API_AUTH_KEY is not configured");
        return false;
    }

    private boolean isSafeReadOnlyRequest(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        return READ_ONLY_WHITELIST.contains(path);
    }

    private boolean isLocalRequest(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        return "127.0.0.1".equals(remoteAddr)
                || "0:0:0:0:0:0:0:1".equals(remoteAddr)
                || "::1".equals(remoteAddr);
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private void reject(HttpServletResponse response, String message) throws Exception {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"success\":false,\"msg\":\"" + message + "\"}");
    }
}
