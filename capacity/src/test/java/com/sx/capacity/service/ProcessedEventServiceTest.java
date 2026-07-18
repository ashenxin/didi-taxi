package com.sx.capacity.service;

import com.sx.capacity.model.CapacityProcessedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProcessedEventServiceTest {

    @Autowired
    private ProcessedEventService service;

    @Test
    void duplicateEventInSameConsumerGroupIsClaimedOnlyOnce() {
        String consumerGroup = "capacity.test.group";
        String eventId = "EVENT-IDEMPOTENT-1";

        assertThat(service.tryMarkProcessed(consumerGroup, eventId)).isTrue();
        service.recordResult(consumerGroup, eventId, "SUCCESS", "ORDER-1", 80001L, null);
        assertThat(service.tryMarkProcessed(consumerGroup, eventId)).isFalse();

        CapacityProcessedEvent stored = service.get(consumerGroup, eventId);
        assertThat(stored.getResultStatus()).isEqualTo("SUCCESS");
        assertThat(stored.getOrderNo()).isEqualTo("ORDER-1");
        assertThat(stored.getDriverId()).isEqualTo(80001L);
    }

    @Test
    void sameEventIdCanBeClaimedByDifferentConsumerGroups() {
        String eventId = "EVENT-IDEMPOTENT-2";

        assertThat(service.tryMarkProcessed("capacity.test.group.a", eventId)).isTrue();
        assertThat(service.tryMarkProcessed("capacity.test.group.b", eventId)).isTrue();
    }
}
