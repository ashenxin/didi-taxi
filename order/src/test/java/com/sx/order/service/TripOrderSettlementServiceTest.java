package com.sx.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.order.dao.TripOrderEntityMapper;
import com.sx.order.dao.TripOrderSettlementMapper;
import com.sx.order.model.TripOrder;
import com.sx.order.model.TripOrderSettlement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class TripOrderSettlementServiceTest {

    @Autowired
    private TripOrderSettlementService service;
    @Autowired
    private TripOrderEntityMapper orderMapper;
    @Autowired
    private TripOrderSettlementMapper settlementMapper;

    @BeforeEach
    void clean() {
        settlementMapper.delete(null);
        orderMapper.delete(null);
    }

    @Test
    void firstProcessingFreezesMockDurationAndReplayKeepsOriginalValue() {
        TripOrder order = finishedOrder();
        orderMapper.insert(order);
        settlementMapper.insert(calculatingSettlement());

        service.process(order.getOrderNo());
        TripOrder first = order(order.getOrderNo());
        assertThat(first.getMockActualDurationSeconds()).isEqualTo(1_560L);
        assertThat(first.getDurationSource()).isEqualTo("LOCAL_MOCK_TRIP");
        assertThat(first.getTripMetricsVersion()).isEqualTo("mock-trip-v1");

        first.setStartedAt(first.getStartedAt().minusHours(3));
        first.setFinishedAt(first.getFinishedAt().plusHours(5));
        orderMapper.updateById(first);
        service.process(order.getOrderNo());

        assertThat(order(order.getOrderNo()).getMockActualDurationSeconds()).isEqualTo(1_560L);
    }

    @Test
    void missingFrozenPricingSnapshotStopsBeforeGeneratingMetrics() {
        TripOrder order = finishedOrder();
        order.setFareRuleSnapshot(null);
        orderMapper.insert(order);
        settlementMapper.insert(calculatingSettlement());

        service.process(order.getOrderNo());

        TripOrderSettlement settlement = settlement(order.getOrderNo());
        assertThat(settlement.getSettlementStatus()).isEqualTo("CALCULATING");
        assertThat(settlement.getFailureCode()).isEqualTo("MISSING_PRICING_SNAPSHOT");
        assertThat(settlement.getManualActionRequired()).isEqualTo(1);
        assertThat(order(order.getOrderNo()).getMockActualDurationSeconds()).isNull();
    }

    private TripOrder order(String orderNo) {
        return orderMapper.selectOne(Wrappers.<TripOrder>lambdaQuery().eq(TripOrder::getOrderNo, orderNo));
    }

    private TripOrderSettlement settlement(String orderNo) {
        return settlementMapper.selectOne(Wrappers.<TripOrderSettlement>lambdaQuery()
                .eq(TripOrderSettlement::getOrderNo, orderNo));
    }

    private static TripOrderSettlement calculatingSettlement() {
        LocalDateTime now = LocalDateTime.now();
        return new TripOrderSettlement()
                .setOrderNo("T202607170010")
                .setPassengerId(10001L)
                .setEstimatedAmount(new BigDecimal("35.00"))
                .setCouponDiscountAmount(BigDecimal.ZERO.setScale(2))
                .setPayableAmount(BigDecimal.ZERO.setScale(2))
                .setPaymentStatus(0)
                .setSettlementStatus("CALCULATING")
                .setManualActionRequired(0)
                .setVersion(0)
                .setCreatedAt(now)
                .setUpdatedAt(now);
    }

    private static TripOrder finishedOrder() {
        LocalDateTime now = LocalDateTime.now();
        return new TripOrder()
                .setOrderNo("T202607170010")
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
                .setStatus(5)
                .setEstimatedAmount(new BigDecimal("35.00"))
                .setFareRuleId(7L)
                .setFareRuleSnapshot("{\"ruleId\":7,\"baseFare\":12.00}")
                .setFareCalculationVersion("fare-v1")
                .setPlannedDistanceMeters(12_340L)
                .setPlannedDurationSeconds(1_560L)
                .setDistanceSource("LOCAL_MOCK_ROUTE")
                .setRouteMockVersion("mock-route-v1")
                .setBlocksNewOrder(1)
                .setOfferRound(1)
                .setStartedAt(now.minusMinutes(30))
                .setFinishedAt(now)
                .setCreatedAt(now.minusHours(1))
                .setUpdatedAt(now)
                .setIsDeleted(0);
    }
}
