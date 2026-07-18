package com.sx.order.outbox;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sx.order.dao.OrderOutboxEventMapper;
import com.sx.order.model.OrderOutboxEvent;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxPublisherJobTest {

    private final OrderOutboxEventMapper mapper = mock(OrderOutboxEventMapper.class);
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private OutboxPublisherJob job;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), "order-outbox-test"),
                OrderOutboxEvent.class);
        job = new OutboxPublisherJob(mapper, kafkaTemplate);
        ReflectionTestUtils.setField(job, "batchSize", 50);
        ReflectionTestUtils.setField(job, "processingTimeoutSeconds", 300);
        ReflectionTestUtils.setField(job, "maxRetryCount", 3);
    }

    @Test
    void claimedEventIsPublishedWithOrderNumberAsKafkaKey() throws Exception {
        OrderOutboxEvent event = event();
        when(mapper.selectList(any())).thenReturn(List.of(event));
        when(mapper.update(any(), any())).thenReturn(0, 1, 1);
        when(kafkaTemplate.send("order.dispatch.requested.v1", "ORDER-1", "{\"eventId\":1}"))
                .thenReturn(CompletableFuture.completedFuture(null));

        job.publish();

        verify(kafkaTemplate).send("order.dispatch.requested.v1", "ORDER-1", "{\"eventId\":1}");
        verify(mapper, times(3)).update(any(), any());
    }

    @Test
    void eventLostInClaimRaceIsNotPublished() throws Exception {
        when(mapper.selectList(any())).thenReturn(List.of(event()));
        when(mapper.update(any(), any())).thenReturn(0, 0);

        job.publish();

        verify(kafkaTemplate, never()).send(any(), any(), any());
        verify(mapper, times(2)).update(any(), any());
    }

    private static OrderOutboxEvent event() {
        return new OrderOutboxEvent()
                .setId(1L)
                .setTopic("order.dispatch.requested.v1")
                .setAggregateId("ORDER-1")
                .setPayload("{\"eventId\":1}")
                .setStatus("PENDING")
                .setRetryCount(0)
                .setNextRetryAt(LocalDateTime.now().minusSeconds(1));
    }
}
