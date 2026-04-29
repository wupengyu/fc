package cn.daenx.myadmin.common.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Slf4j
@Component
public class ApiAuthInterceptor implements HandlerInterceptor {

    @Value("${api.auth.key:}")
    private String apiKey;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) {
            return true;
        }
        String requestKey = request.getHeader("X-API-Key");
        if (apiKey.equals(requestKey)) {
            return true;
        }
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"success\":false,\"msg\":\"Unauthorized\"}");
        return false;
    }
}
