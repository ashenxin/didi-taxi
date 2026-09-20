package com.sx.passengerapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

/** AI 客服轮次执行的线程池：controller 立即返回 SseEmitter，编排在池内推进。 */
@Configuration
public class AiTurnExecutorConfiguration {

    @Bean(name = "aiTurnExecutor")
    public ThreadPoolTaskExecutor aiTurnExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(8);
        executor.setThreadNamePrefix("ai-turn-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(90);
        return executor;
    }

    @Bean(name = "aiSseHeartbeatScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService aiSseHeartbeatScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "ai-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
