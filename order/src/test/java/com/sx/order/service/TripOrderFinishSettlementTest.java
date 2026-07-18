package com.sx.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.order.dao.OrderEventEntityMapper;
import com.sx.order.dao.OrderOutboxEventMapper;
import com.sx.order.dao.TripOrderEntityMapper;
import com.sx.order.dao.TripOrderSettlementMapper;
import com.sx.order.model.OrderOutboxEvent;
import com.sx.order.model.TripOrder;
import com.sx.order.model.TripOrderSettlement;
import com.sx.order.model.dto.FinishOrderBody;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class TripOrderFinishSettlementTest {

    @Autowired
    private TripOrderWriteService service;
    @Autowired
    private TripOrderEntityMapper orderMapper;
    @Autowired
    private TripOrderSettlementMapper settlementMapper;
    @Autowired
    private OrderEventEntityMapper eventMapper;
    @Autowired
    private OrderOutboxEventMapper outboxMapper;

    @BeforeEach
    void clean() {
        settlementMapper.delete(null);
        outboxMapper.delete(null);
        eventMapper.delete(null);
        orderMapper.delete(null);
    }

    @Test
    void finishAtomicallyCreatesOneCalculatingSettlementAndOutboxWithoutTrustingDriverAmounts() {
        orderMapper.insert(startedOrder());
        FinishOrderBody body = new FinishOrderBody();
        body.setDriverId(80001L);
        body.setDistanceKm(new BigDecimal("9999.00"));
        body.setDurationMin(9999);
        body.setFinalAmount(new BigDecimal("0.01"));

        service.finish("T202607170001", body, "finish-idempotency-key");
        service.finish("T202607170001", body, "finish-idempotency-key");

        TripOrder order = orderMapper.selectOne(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, "T202607170001"));
        assertThat(order.getStatus()).isEqualTo(5);
        assertThat(order.getFinalAmount()).isNull();
        assertThat(order.getFinishedAt()).isNotNull();
        TripOrderSettlement settlement = settlementMapper.selectOne(Wrappers.<TripOrderSettlement>lambdaQuery()
                .eq(TripOrderSettlement::getOrderNo, "T202607170001"));
        assertThat(settlement.getSettlementStatus()).isEqualTo("CALCULATING");
        assertThat(settlement.getPaymentNo()).isNull();
        assertThat(settlement.getPaymentStatus()).isZero();
        assertThat(settlement.getManualActionRequired()).isZero();
        assertThat(settlementMapper.selectCount(null)).isEqualTo(1);
        assertThat(eventMapper.selectCount(Wrappers.<com.sx.order.model.OrderEvent>lambdaQuery()
                .eq(com.sx.order.model.OrderEvent::getEventType, "ORDER_FINISHED"))).isEqualTo(1);
        OrderOutboxEvent outbox = outboxMapper.selectOne(Wrappers.<OrderOutboxEvent>lambdaQuery()
                .eq(OrderOutboxEvent::getTopic, "order.settlement.requested.v1"));
        assertThat(outbox.getEventType()).isEqualTo("ORDER_FINISHED_NEED_SETTLEMENT");
        assertThat(outbox.getAggregateId()).isEqualTo("T202607170001");
        OrderOutboxEvent changed = outboxMapper.selectOne(Wrappers.<OrderOutboxEvent>lambdaQuery()
                .eq(OrderOutboxEvent::getTopic, "order.changed.v1"));
        assertThat(changed.getEventType()).isEqualTo("ORDER_CHANGED");
        assertThat(changed.getPayload()).contains("\"eventType\":\"ORDER_CHANGED\"")
                .doesNotContain("amount");
        assertThat(outboxMapper.selectCount(null)).isEqualTo(2);
    }

    @Test
    void concurrentFinishCallsConvergeToOneSettlementTask() throws Exception {
        orderMapper.insert(startedOrder());
        FinishOrderBody body = new FinishOrderBody();
        body.setDriverId(80001L);
        int callers = 6;
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    service.finish("T202607170001", body, "finish-concurrent-" + Thread.currentThread().threadId());
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(settlementMapper.selectCount(null)).isEqualTo(1);
        assertThat(outboxMapper.selectCount(Wrappers.<OrderOutboxEvent>lambdaQuery()
                .eq(OrderOutboxEvent::getTopic, "order.settlement.requested.v1"))).isEqualTo(1);
    }

    private static TripOrder startedOrder() {
        LocalDateTime now = LocalDateTime.now();
        return new TripOrder()
                .setOrderNo("T202607170001")
                .setPassengerId(10001L)
                .setDriverId(80001L)
                .setCompanyId(9L)
                .setProductCode("ECONOMY")
                .setProvinceCode("330000")
                .setCityCode("330100")
                .setOriginAddress("杭州东站")
                .setOriginLat(new BigDecimal("30.2912000"))
                .setOriginLng(new BigDecimal("120.2120000"))
                .setDestAddress("龙翔桥")
                .setDestLat(new BigDecimal("30.2592000"))
                .setDestLng(new BigDecimal("120.1640000"))
                .setStatus(4)
                .setEstimatedAmount(new BigDecimal("35.00"))
                .setFareRuleId(7L)
                .setFareRuleSnapshot("{\"baseFare\":12.00}")
                .setFareCalculationVersion("fare-v1")
                .setPlannedDistanceMeters(12_340L)
                .setPlannedDurationSeconds(1_560L)
                .setDistanceSource("LOCAL_MOCK_ROUTE")
                .setRouteMockVersion("mock-route-v1")
                .setBlocksNewOrder(1)
                .setOfferRound(1)
                .setCreatedAt(now.minusMinutes(30))
                .setStartedAt(now.minusMinutes(20))
                .setUpdatedAt(now)
                .setIsDeleted(0);
    }
}
