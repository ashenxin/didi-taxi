package com.sx.passengerapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** AI 客服轮次执行的线程池：controller 立即返回 SseEmitter，编排在池内推进。 */
@Configuration
public class AiTurnExecutorConfiguration {

    @Bean(name = "aiTurnExecutor")
    public ThreadPoolTaskExecutor aiTurnExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("ai-turn-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }
}
