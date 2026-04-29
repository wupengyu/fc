package cn.daenx.myadmin.server.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    @Value("${task.async.core-size:1}")
    private int coreSize;

    @Value("${task.async.max-size:1}")
    private int maxSize;

    @Value("${task.async.queue-capacity:20}")
    private int queueCapacity;

    @Bean(name = "parseTaskExecutor")
    public ThreadPoolTaskExecutor parseTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int resolvedCoreSize = Math.max(1, coreSize);
        int resolvedMaxSize = Math.max(resolvedCoreSize, maxSize);
        executor.setCorePoolSize(resolvedCoreSize);
        executor.setMaxPoolSize(resolvedMaxSize);
        executor.setQueueCapacity(Math.max(1, queueCapacity));
        executor.setThreadNamePrefix("async-parse-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return parseTaskExecutor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (Throwable ex, Method method, Object... params) ->
                log.error("async task failed, method={}", method.getName(), ex);
    }
}
