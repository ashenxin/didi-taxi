package com.sx.order.lifecycle;

import com.sx.order.dao.OrderEventEntityMapper;
import com.sx.order.dao.OrderIdempotentRecordMapper;
import com.sx.order.dao.OrderOutboxEventMapper;
import com.sx.order.dao.TripOrderEntityMapper;
import com.sx.order.lifecycle.dao.OrderAccountLifecycleEventInboxMapper;
import com.sx.order.lifecycle.dao.OrderAccountLifecycleProjectionMapper;
import com.sx.order.lifecycle.dao.OrderLifecycleParticipantInboxMapper;
import com.sx.order.lifecycle.exception.AccountLifecycleBlockedException;
import com.sx.order.lifecycle.model.ApplyOrderLifecycleProjectionCommand;
import com.sx.order.lifecycle.model.OrderLifecycleCommand;
import com.sx.order.lifecycle.model.OrderLifecycleDecision;
import com.sx.order.lifecycle.model.OrderWriteAction;
import com.sx.order.lifecycle.service.AccountLifecycleOrderParticipantService;
import com.sx.order.lifecycle.service.AccountWriteFence;
import com.sx.order.lifecycle.service.OrderLifecycleProjectionService;
import com.sx.order.model.dto.CreateOrderBody;
import com.sx.order.model.dto.Place;
import com.sx.order.service.TripOrderWriteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

@SpringBootTest
@ActiveProfiles("test")
class AccountLifecycleOrderConcurrencyTest {
    @Autowired private TripOrderWriteService orders;
    @Autowired private AccountLifecycleOrderParticipantService participant;
    @SpyBean private AccountWriteFence writeFence;
    @SpyBean private OrderLifecycleProjectionService projections;
    @Autowired private OrderAccountLifecycleProjectionMapper projectionMapper;
    @Autowired private OrderAccountLifecycleEventInboxMapper eventInboxMapper;
    @Autowired private OrderLifecycleParticipantInboxMapper participantInboxMapper;
    @Autowired private TripOrderEntityMapper orderMapper;
    @Autowired private OrderEventEntityMapper eventMapper;
    @Autowired private OrderOutboxEventMapper outboxMapper;
    @Autowired private OrderIdempotentRecordMapper idempotentMapper;

    @BeforeEach
    void clean() {
        reset(writeFence, projections);
        outboxMapper.delete(null);
        eventMapper.delete(null);
        orderMapper.delete(null);
        idempotentMapper.delete(null);
        participantInboxMapper.delete(null);
        projectionMapper.delete(null);
        eventInboxMapper.delete(null);
    }

    @Test
    void createHoldingProjectionLockCommitsBeforeFenceWhichThenSeesActiveOrder() throws Exception {
        long customerId = 13001L;
        projections.seedActive(customerId, "seed-concurrency-1", LocalDateTime.now());
        CountDownLatch createLocked = new CountDownLatch(1);
        CountDownLatch releaseCreate = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            createLocked.countDown();
            assertThat(releaseCreate.await(5, TimeUnit.SECONDS)).isTrue();
            return result;
        }).when(writeFence).lockAndRequireAllowed(eq(customerId), eq(OrderWriteAction.RIDE_CREATE));

        var pool = Executors.newFixedThreadPool(2);
        try {
            var createFuture = pool.submit(() -> orders.create(body(customerId), "create-wins"));
            assertThat(createLocked.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch fenceStarted = new CountDownLatch(1);
            var fenceFuture = pool.submit(() -> {
                fenceStarted.countDown();
                return participant.fence(command("op-create-wins", customerId, "event-create-wins"));
            });
            assertThat(fenceStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseCreate.countDown();

            assertThat(createFuture.get(5, TimeUnit.SECONDS)).isNotBlank();
            var result = fenceFuture.get(5, TimeUnit.SECONDS);
            assertThat(result.decision()).isEqualTo(OrderLifecycleDecision.BLOCKED);
            assertThat(result.blockers()).extracting("code").containsExactly("ACTIVE_ORDER");
            assertThat(orderMapper.selectCount(null)).isEqualTo(1);
            assertThat(participantInboxMapper.selectCount(null)).isEqualTo(1);
        } finally {
            releaseCreate.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void fenceHoldingProjectionLockCommitsBeforeCreateWhichThenFailsClosed() throws Exception {
        long customerId = 13002L;
        projections.seedActive(customerId, "seed-concurrency-2", LocalDateTime.now());
        CountDownLatch fenceLocked = new CountDownLatch(1);
        CountDownLatch releaseFence = new CountDownLatch(1);
        doAnswer(invocation -> {
            ApplyOrderLifecycleProjectionCommand command = invocation.getArgument(0);
            Object result = invocation.callRealMethod();
            if ("event-fence-wins".equals(command.sourceEventId())) {
                fenceLocked.countDown();
                assertThat(releaseFence.await(5, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(projections).applyUnderLock(any(ApplyOrderLifecycleProjectionCommand.class));

        var pool = Executors.newFixedThreadPool(2);
        try {
            var fenceFuture = pool.submit(() -> participant.fence(
                    command("op-fence-wins", customerId, "event-fence-wins")));
            assertThat(fenceLocked.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch createStarted = new CountDownLatch(1);
            var createFuture = pool.submit(() -> {
                createStarted.countDown();
                return orders.create(body(customerId), "fence-wins");
            });
            assertThat(createStarted.await(5, TimeUnit.SECONDS)).isTrue();
            releaseFence.countDown();

            assertThat(fenceFuture.get(5, TimeUnit.SECONDS).decision())
                    .isEqualTo(OrderLifecycleDecision.PASS);
            assertThatThrownBy(() -> get(createFuture))
                    .isInstanceOf(AccountLifecycleBlockedException.class);
            assertThat(orderMapper.selectCount(null)).isZero();
            assertThat(participantInboxMapper.selectCount(null)).isEqualTo(1);
            assertThat(projectionMapper.selectById(customerId).getLifecycleStatus())
                    .isEqualTo("CANCELLING");
        } finally {
            releaseFence.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentIdenticalParticipantCommandsProduceOneStableInboxResult() throws Exception {
        long customerId = 13003L;
        projections.seedActive(customerId, "seed-concurrency-3", LocalDateTime.now());
        OrderLifecycleCommand command = command("op-concurrent-replay", customerId,
                "event-concurrent-replay");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> concurrentFence(command, ready, start));
            var second = pool.submit(() -> concurrentFence(command, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
            assertThat(participantInboxMapper.selectCount(null)).isEqualTo(1);
            assertThat(projectionMapper.selectById(customerId).getLifecycleVersion()).isEqualTo(1L);
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    private com.sx.order.lifecycle.model.OrderLifecycleParticipantResult concurrentFence(
            OrderLifecycleCommand command, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        return participant.fence(command);
    }

    private static Object get(java.util.concurrent.Future<?> future) throws Throwable {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            throw ex.getCause();
        }
    }

    private static OrderLifecycleCommand command(String operationNo, long customerId, String eventId) {
        return new OrderLifecycleCommand(operationNo, "ORDER_FINAL_CHECK", customerId,
                "CANCELLING", 1L, eventId, LocalDateTime.now());
    }

    private static CreateOrderBody body(Long passengerId) {
        CreateOrderBody body = new CreateOrderBody();
        body.setPassengerId(passengerId);
        body.setProvinceCode("330000");
        body.setCityCode("330100");
        body.setProductCode("ECONOMY");
        body.setOrigin(place("起点", "30.1", "120.1"));
        body.setDest(place("终点", "30.2", "120.2"));
        body.setEstimatedAmount(new BigDecimal("30.00"));
        body.setFareRuleId(1L);
        body.setFareRuleSnapshot("{\"baseFare\":12.00}");
        body.setFareCalculationVersion("fare-v1");
        body.setPlannedDistanceMeters(10000L);
        body.setPlannedDurationSeconds(1200L);
        body.setDistanceSource("LOCAL_MOCK_ROUTE");
        body.setRouteMockVersion("mock-route-v1");
        return body;
    }

    private static Place place(String address, String lat, String lng) {
        Place place = new Place();
        place.setAddress(address);
        place.setLat(new BigDecimal(lat));
        place.setLng(new BigDecimal(lng));
        return place;
    }
}
