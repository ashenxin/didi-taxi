package com.sx.order.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.order.dao.TripOrderEntityMapper;
import com.sx.order.dao.TripOrderSettlementMapper;
import com.sx.order.model.TripOrder;
import com.sx.order.model.TripOrderSettlement;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
@Slf4j
public class TripOrderSettlementService {

    private static final int STATUS_FINISHED = 5;

    private final TripOrderEntityMapper orderMapper;
    private final TripOrderSettlementMapper settlementMapper;
    private final MockTripMetricsProvider metricsProvider;

    public TripOrderSettlementService(TripOrderEntityMapper orderMapper,
                                      TripOrderSettlementMapper settlementMapper,
                                      MockTripMetricsProvider metricsProvider) {
        this.orderMapper = orderMapper;
        this.settlementMapper = settlementMapper;
        this.metricsProvider = metricsProvider;
    }

    @Transactional
    public void process(String orderNo) {
        TripOrder order = getOrder(orderNo);
        TripOrderSettlement settlement = getSettlement(orderNo);
        if (order == null || settlement == null) {
            log.error("结算任务缺少订单或结算记录 orderNo={}", orderNo);
            return;
        }
        if ("PAID".equals(settlement.getSettlementStatus())) {
            return;
        }
        if (!Integer.valueOf(STATUS_FINISHED).equals(order.getStatus())) {
            markFailure(settlement, "ORDER_NOT_FINISHED", "订单尚未完单", true);
            return;
        }
        if (!hasFrozenPricingInputs(order)) {
            markFailure(settlement, "MISSING_PRICING_SNAPSHOT", "缺少冻结路线指标、计价规则快照或版本", true);
            return;
        }
        freezeMockActualDuration(order);
        // 后续阶段在 Task 6 接入最终计价、选券和支付；此处已保证输入快照幂等就绪。
        clearRecoverableFailure(settlement);
    }

    private void freezeMockActualDuration(TripOrder order) {
        if (order.getMockActualDurationSeconds() != null) {
            return;
        }
        long generated = metricsProvider.generateDurationSeconds(
                order.getOrderNo(), order.getPlannedDurationSeconds());
        LocalDateTime now = LocalDateTime.now();
        orderMapper.update(null, Wrappers.<TripOrder>lambdaUpdate()
                .set(TripOrder::getMockActualDurationSeconds, generated)
                .set(TripOrder::getDurationSource, "LOCAL_MOCK_TRIP")
                .set(TripOrder::getTripMetricsVersion, metricsProvider.version())
                .set(TripOrder::getUpdatedAt, now)
                .eq(TripOrder::getOrderNo, order.getOrderNo())
                .eq(TripOrder::getIsDeleted, 0)
                .isNull(TripOrder::getMockActualDurationSeconds));
    }

    private boolean hasFrozenPricingInputs(TripOrder order) {
        return order.getPlannedDistanceMeters() != null && order.getPlannedDistanceMeters() >= 0
                && order.getPlannedDurationSeconds() != null && order.getPlannedDurationSeconds() > 0
                && "LOCAL_MOCK_ROUTE".equals(order.getDistanceSource())
                && StringUtils.hasText(order.getRouteMockVersion())
                && StringUtils.hasText(order.getFareRuleSnapshot())
                && StringUtils.hasText(order.getFareCalculationVersion());
    }

    private void clearRecoverableFailure(TripOrderSettlement settlement) {
        if (settlement.getFailureCode() == null && Integer.valueOf(0).equals(settlement.getManualActionRequired())) {
            return;
        }
        settlementMapper.update(null, Wrappers.<TripOrderSettlement>lambdaUpdate()
                .set(TripOrderSettlement::getFailureCode, null)
                .set(TripOrderSettlement::getFailureSummary, null)
                .set(TripOrderSettlement::getManualActionRequired, 0)
                .set(TripOrderSettlement::getUpdatedAt, LocalDateTime.now())
                .eq(TripOrderSettlement::getId, settlement.getId()));
    }

    private void markFailure(TripOrderSettlement settlement, String code, String summary, boolean manual) {
        settlementMapper.update(null, Wrappers.<TripOrderSettlement>lambdaUpdate()
                .set(TripOrderSettlement::getFailureCode, code)
                .set(TripOrderSettlement::getFailureSummary, summary)
                .set(TripOrderSettlement::getManualActionRequired, manual ? 1 : 0)
                .set(TripOrderSettlement::getUpdatedAt, LocalDateTime.now())
                .eq(TripOrderSettlement::getId, settlement.getId())
                .eq(TripOrderSettlement::getSettlementStatus, "CALCULATING"));
    }

    private TripOrder getOrder(String orderNo) {
        return orderMapper.selectOne(Wrappers.<TripOrder>lambdaQuery()
                .eq(TripOrder::getOrderNo, orderNo)
                .eq(TripOrder::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private TripOrderSettlement getSettlement(String orderNo) {
        return settlementMapper.selectOne(Wrappers.<TripOrderSettlement>lambdaQuery()
                .eq(TripOrderSettlement::getOrderNo, orderNo)
                .last("LIMIT 1"));
    }
}
