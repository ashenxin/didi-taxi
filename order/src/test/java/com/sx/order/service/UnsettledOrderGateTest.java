package com.sx.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.order.common.exception.UnsettledOrderException;
import com.sx.order.dao.OrderEventEntityMapper;
import com.sx.order.dao.OrderIdempotentRecordMapper;
import com.sx.order.dao.OrderOutboxEventMapper;
import com.sx.order.dao.TripOrderEntityMapper;
import com.sx.order.dao.TripOrderSettlementMapper;
import com.sx.order.model.TripOrder;
import com.sx.order.model.TripOrderSettlement;
import com.sx.order.model.dto.CreateOrderBody;
import com.sx.order.model.dto.Place;
import com.sx.order.lifecycle.dao.OrderAccountLifecycleProjectionMapper;
import com.sx.order.lifecycle.model.ApplyOrderLifecycleProjectionCommand;
import com.sx.order.lifecycle.model.OrderLifecycleStatus;
import com.sx.order.lifecycle.service.OrderLifecycleProjectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
@ActiveProfiles("test")
class UnsettledOrderGateTest {
    @Autowired private TripOrderWriteService service;
    @Autowired private TripOrderEntityMapper orderMapper;
    @Autowired private TripOrderSettlementMapper settlementMapper;
    @Autowired private OrderEventEntityMapper eventMapper;
    @Autowired private OrderOutboxEventMapper outboxMapper;
    @Autowired private OrderIdempotentRecordMapper idempotentMapper;
    @Autowired private OrderLifecycleProjectionService lifecycleProjectionService;
    @Autowired private OrderAccountLifecycleProjectionMapper lifecycleProjectionMapper;

    @BeforeEach
    void clean() {
        settlementMapper.delete(null);
        outboxMapper.delete(null);
        eventMapper.delete(null);
        orderMapper.delete(null);
        idempotentMapper.delete(null);
        lifecycleProjectionMapper.delete(null);
        for (long customerId = 91001L; customerId <= 91012L; customerId++) {
            seedActive(customerId);
        }
    }

    @Test
    void paymentRequiredBlocksWithGoToPaymentAction() {
        TripOrder old = blockingOrder(91001L, 5);
        orderMapper.insert(old);
        settlementMapper.insert(settlement(old, "PAYMENT_REQUIRED", 0));

        UnsettledOrderException error = catchThrowableOfType(
                () -> service.create(body(91001L), "new-key"), UnsettledOrderException.class);

        assertThat(error.getResult().blockingOrderNo()).isEqualTo(old.getOrderNo());
        assertThat(error.getResult().settlementStatus()).isEqualTo("PAYMENT_REQUIRED");
        assertThat(error.getResult().action()).isEqualTo("GO_TO_PAYMENT");
        assertThat(settlementMapper.countPassengerUnsettledOrders(91001L)).isEqualTo(1L);
    }

    @Test
    void calculatingWaitsButManualOrMissingSettlementContactsOperations() {
        TripOrder calculating = blockingOrder(91002L, 5);
        orderMapper.insert(calculating);
        settlementMapper.insert(settlement(calculating, "CALCULATING", 0));
        assertThat(blocked(91002L, "calc").getResult().action()).isEqualTo("WAIT");

        clean();
        TripOrder manual = blockingOrder(91003L, 5);
        orderMapper.insert(manual);
        settlementMapper.insert(settlement(manual, "CALCULATING", 1));
        assertThat(blocked(91003L, "manual").getResult().action()).isEqualTo("CONTACT_OPERATIONS");

        clean();
        TripOrder missing = blockingOrder(91004L, 5);
        orderMapper.insert(missing);
        assertThat(blocked(91004L, "missing").getResult().action()).isEqualTo("CONTACT_OPERATIONS");
    }

    @Test
    void activeAndConfirmingWaitWhilePaidAndCancelledAllowBooking() {
        TripOrder active = blockingOrder(91005L, 2);
        orderMapper.insert(active);
        assertThat(blocked(91005L, "active").getResult().action()).isEqualTo("WAIT");

        clean();
        TripOrder confirming = blockingOrder(91006L, 5);
        orderMapper.insert(confirming);
        settlementMapper.insert(settlement(confirming, "PAY_CONFIRMING", 0));
        assertThat(blocked(91006L, "confirming").getResult().action()).isEqualTo("WAIT");

        clean();
        TripOrder paid = blockingOrder(91007L, 5);
        orderMapper.insert(paid);
        settlementMapper.insert(settlement(paid, "PAID", 0));
        assertThat(settlementMapper.countPassengerUnsettledOrders(91007L)).isZero();
        assertThat(service.create(body(91007L), "paid")).isNotBlank();

        clean();
        TripOrder cancelled = blockingOrder(91008L, 6);
        orderMapper.insert(cancelled);
        assertThat(service.create(body(91008L), "cancelled")).isNotBlank();
    }

    @Test
    void concurrentDifferentIdempotencyKeysCreateOnlyOneBlockingOrder() throws Exception {
        long passengerId = 91009L;
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> first = pool.submit(() -> createConcurrently(passengerId, "race-a", ready, start));
            Future<Object> second = pool.submit(() -> createConcurrently(passengerId, "race-b", ready, start));
            ready.await();
            start.countDown();

            List<Object> results = List.of(first.get(), second.get());
            assertThat(results.stream().filter(String.class::isInstance)).hasSize(1);
            assertThat(results.stream().filter(UnsettledOrderException.class::isInstance)).hasSize(1);
            assertThat(orderMapper.selectCount(Wrappers.<TripOrder>lambdaQuery()
                    .eq(TripOrder::getPassengerId, passengerId)
                    .eq(TripOrder::getBlocksNewOrder, 1))).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void terminalCancellationPathsClearBlockingFlag() {
        TripOrder passengerCancelled = blockingOrder(91010L, 0);
        orderMapper.insert(passengerCancelled);
        com.sx.order.model.dto.CancelOrderBody cancel = new com.sx.order.model.dto.CancelOrderBody();
        cancel.setPassengerId(91010L);
        cancel.setCancelReason("计划有变");

        service.cancelByPassenger(passengerCancelled.getOrderNo(), cancel, "passenger-cancel-key");

        assertThat(order(passengerCancelled.getOrderNo()).getBlocksNewOrder()).isNull();

        TripOrder timeout = blockingOrder(91011L, 0);
        orderMapper.insert(timeout);
        assertThat(service.cancelCreatedDispatchTimeoutOne(timeout.getOrderNo(), LocalDateTime.now())).isTrue();
        assertThat(order(timeout.getOrderNo()).getBlocksNewOrder()).isNull();
    }

    @Test
    void successfulIdempotencyReplayIsNotBlockedByItsOwnOrder() {
        String originalOrderNo = service.create(body(91012L), "lost-response-key");

        assertThat(service.findBlockingOrder(91012L, "lost-response-key")).isNull();
        assertThat(service.create(body(91012L), "lost-response-key")).isEqualTo(originalOrderNo);
        assertThat(service.findBlockingOrder(91012L, "different-key").action()).isEqualTo("WAIT");
    }

    private Object createConcurrently(long passengerId, String key, CountDownLatch ready,
                                      CountDownLatch start) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return service.create(body(passengerId), key);
        } catch (UnsettledOrderException ex) {
            return ex;
        }
    }

    private TripOrder order(String orderNo) {
        return orderMapper.selectOne(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, orderNo));
    }

    private UnsettledOrderException blocked(Long passengerId, String key) {
        return catchThrowableOfType(() -> service.create(body(passengerId), key),
                UnsettledOrderException.class);
    }

    private static TripOrder blockingOrder(Long passengerId, int status) {
        LocalDateTime now = LocalDateTime.now();
        return new TripOrder().setOrderNo("OLD-" + passengerId).setPassengerId(passengerId)
                .setProductCode("ECONOMY").setProvinceCode("330000").setCityCode("330100")
                .setOriginAddress("起点").setOriginLat(new BigDecimal("30.1"))
                .setOriginLng(new BigDecimal("120.1"))
                .setDestAddress("终点").setDestLat(new BigDecimal("30.2"))
                .setDestLng(new BigDecimal("120.2"))
                .setStatus(status).setBlocksNewOrder(1).setOfferRound(0)
                .setCreatedAt(now).setUpdatedAt(now).setIsDeleted(0);
    }

    private static TripOrderSettlement settlement(TripOrder order, String status, int manual) {
        LocalDateTime now = LocalDateTime.now();
        return new TripOrderSettlement().setOrderNo(order.getOrderNo()).setPassengerId(order.getPassengerId())
                .setSettlementStatus(status).setPaymentStatus("PAID".equals(status) ? 2 : 0)
                .setManualActionRequired(manual).setCreatedAt(now).setUpdatedAt(now);
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

    private void seedActive(long customerId) {
        lifecycleProjectionService.apply(new ApplyOrderLifecycleProjectionCommand(
                customerId, 0, OrderLifecycleStatus.ACTIVE.name(), 0,
                null, "test-seed-" + customerId, LocalDateTime.now()));
    }
}
