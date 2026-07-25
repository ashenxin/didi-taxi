package com.sx.passenger.lifecycle.messaging;

import com.sx.passenger.lifecycle.job.LifecycleJobBatchResult;
import com.sx.passenger.lifecycle.job.LifecycleJobProperties;
import com.sx.passenger.lifecycle.persistence.entity.LifecycleOutboxEntity;
import com.sx.passenger.lifecycle.persistence.mapper.LifecycleOutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KafkaLifecycleOutboxPublisherTest {
    private LifecycleOutboxMapper outbox;
    private KafkaTemplate<String, String> kafka;
    private KafkaLifecycleOutboxPublisher publisher;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        outbox = mock(LifecycleOutboxMapper.class);
        kafka = mock(KafkaTemplate.class);
        PlatformTransactionManager transactionManager =
                mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(new SimpleTransactionStatus());
        publisher = new KafkaLifecycleOutboxPublisher(
                outbox, kafka, new LifecycleJobProperties(), transactionManager);
    }

    @Test
    void reportsSuccessfulPublish() {
        LifecycleOutboxEntity candidate = candidate(0, 10);
        prepareClaim(candidate);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        LifecycleJobBatchResult result = publisher.publishBatch(10);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.claimed()).isEqualTo(1);
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(candidate.getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    void reportsExhaustedPublishWithoutThrowingAwayBatchResult() {
        LifecycleOutboxEntity candidate = candidate(9, 10);
        prepareClaim(candidate);
        CompletableFuture<SendResult<String, String>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("kafka unavailable"));
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(failed);

        LifecycleJobBatchResult result = publisher.publishBatch(10);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.exhausted()).isEqualTo(1);
        assertThat(result.hasTechnicalFailure()).isTrue();
        assertThat(candidate.getStatus()).isEqualTo("FAILED");
        assertThat(candidate.getRetryCount()).isEqualTo(10);
    }

    private void prepareClaim(LifecycleOutboxEntity candidate) {
        when(outbox.findPublishCandidates(any(), any(), anyInt()))
                .thenReturn(List.of(candidate));
        doAnswer(invocation -> {
            candidate.setStatus("PROCESSING")
                    .setProcessingAt(invocation.getArgument(1))
                    .setProcessingBy(invocation.getArgument(3));
            return 1;
        }).when(outbox).claim(anyLong(), any(), any(), anyString());
        when(outbox.selectById(candidate.getId())).thenReturn(candidate);
        when(outbox.updateById(candidate)).thenReturn(1);
    }

    private static LifecycleOutboxEntity candidate(int retryCount, int maxRetryCount) {
        LocalDateTime now = LocalDateTime.now();
        return new LifecycleOutboxEntity()
                .setId(1L)
                .setEventId("event-1")
                .setOperationId(1L)
                .setAggregateType("ACCOUNT_LIFECYCLE")
                .setAggregateId("operation-1")
                .setEventType("ACCOUNT_LIFECYCLE_STEP_COMMAND")
                .setTopic("account.lifecycle.command.v1")
                .setPartitionKey("7")
                .setPayload("{}")
                .setStatus("PENDING")
                .setRetryCount(retryCount)
                .setMaxRetryCount(maxRetryCount)
                .setNextRetryAt(now)
                .setCreatedAt(now)
                .setUpdatedAt(now);
    }
}
