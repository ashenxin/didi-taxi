package com.sx.passenger.ai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 启动及定时收束失联轮次，避免活动请求只能等乘客下一次发言才释放。 */
@Component
public class AiConversationStaleRequestReconciler {

    private static final Logger log = LoggerFactory.getLogger(AiConversationStaleRequestReconciler.class);

    private final AiConversationService conversationService;
    private final int batchSize;

    public AiConversationStaleRequestReconciler(
            AiConversationService conversationService,
            @Value("${passenger.ai.stale-reconcile-batch-size:100}") int batchSize) {
        this.conversationService = conversationService;
        this.batchSize = batchSize;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOnStartup() {
        reconcile("startup");
    }

    @Scheduled(
            initialDelayString = "${passenger.ai.stale-reconcile-interval-ms:30000}",
            fixedDelayString = "${passenger.ai.stale-reconcile-interval-ms:30000}")
    public void reconcilePeriodically() {
        reconcile("scheduled");
    }

    private void reconcile(String trigger) {
        int expired = conversationService.expireStaleRequests(batchSize);
        if (expired > 0) {
            log.info("AI 客服失联请求已收束 trigger={} count={}", trigger, expired);
        }
    }
}
