package com.sx.map.ai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/** AI 路线模型调用使用独立有界线程池，避免占用 JVM 公共 ForkJoinPool。 */
@Configuration(proxyBeanMethods = false)
public class AiRouteExecutionConfiguration {

    @Bean(name = "passengerRouteExtractionExecutor")
    public Executor passengerRouteExtractionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("route-extract-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setAwaitTerminationSeconds(15);
        return executor;
    }
}
