package com.sx.order.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.order.common.util.ResultUtil;
import com.sx.order.common.vo.ResponseVo;
import com.sx.order.dao.TripOrderSettlementMapper;
import com.sx.order.model.TripOrderSettlement;
import com.sx.order.model.dto.SettlementPaymentUpdateRequest;
import com.sx.order.model.dto.SettlementUpsertRequest;
import com.sx.order.model.dto.UnsettledOrderCheckResult;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/v1/orders/internal/settlements")
public class TripOrderSettlementController {
    private static final BigDecimal DEFAULT_SERVICE_FEE_RATE = new BigDecimal("0.0500");

    private final TripOrderSettlementMapper settlementMapper;

    public TripOrderSettlementController(TripOrderSettlementMapper settlementMapper) {
        this.settlementMapper = settlementMapper;
    }

    @PostMapping
    public ResponseVo<TripOrderSettlement> upsert(@Valid @RequestBody SettlementUpsertRequest request) {
        LocalDateTime now = LocalDateTime.now();
        TripOrderSettlement settlement = getByOrderNo(request.getOrderNo());
        if (settlement == null) {
            settlement = new TripOrderSettlement()
                    .setOrderNo(request.getOrderNo())
                    .setPassengerId(request.getPassengerId())
                    .setPaymentStatus(0)
                    .setSettlementStatus(defaultStatus(request.getSettlementStatus(), "CALCULATED"))
                    .setCreatedAt(now);
        }
        settlement.setEstimatedAmount(request.getEstimatedAmount());
        settlement.setFinalAmount(request.getFinalAmount());
        settlement.setCouponId(request.getCouponId());
        settlement.setCouponTemplateId(request.getCouponTemplateId());
        settlement.setCouponCompanyId(request.getCouponCompanyId());
        settlement.setCouponCompanyNo(request.getCouponCompanyNo());
        settlement.setCouponCompanyNameSnapshot(request.getCouponCompanyNameSnapshot());
        settlement.setCouponTeamIdSnapshot(request.getCouponTeamIdSnapshot());
        settlement.setCouponTeamNameSnapshot(request.getCouponTeamNameSnapshot());
        settlement.setCouponType(request.getCouponType());
        settlement.setCouponDiscountAmount(defaultAmount(request.getCouponDiscountAmount()));
        settlement.setCouponRuleSnapshot(request.getCouponRuleSnapshot());
        BigDecimal payableAmount = request.getPayableAmount() == null
                ? defaultAmount(request.getFinalAmount()).subtract(defaultAmount(request.getCouponDiscountAmount())).max(BigDecimal.ZERO)
                : request.getPayableAmount();
        settlement.setPayableAmount(scale(payableAmount));
        BigDecimal feeRate = request.getPlatformServiceFeeRate() == null
                ? DEFAULT_SERVICE_FEE_RATE
                : request.getPlatformServiceFeeRate();
        BigDecimal feeAmount = request.getPlatformServiceFeeAmount() == null
                ? scale(payableAmount.multiply(feeRate))
                : request.getPlatformServiceFeeAmount();
        settlement.setPlatformServiceFeeRate(feeRate);
        settlement.setPlatformServiceFeeAmount(scale(feeAmount));
        settlement.setCarrierIncomeAmount(request.getCarrierIncomeAmount() == null
                ? scale(payableAmount.subtract(feeAmount).max(BigDecimal.ZERO))
                : scale(request.getCarrierIncomeAmount()));
        settlement.setSettlementSnapshot(request.getSettlementSnapshot());
        if (request.getSettlementStatus() != null && !request.getSettlementStatus().isBlank()) {
            settlement.setSettlementStatus(request.getSettlementStatus());
        }
        settlement.setUpdatedAt(now);
        if (settlement.getId() == null) {
            settlementMapper.insert(settlement);
        } else {
            settlementMapper.updateById(settlement);
        }
        return ResultUtil.success(settlement);
    }

    @PostMapping("/{orderNo}/payment")
    public ResponseVo<TripOrderSettlement> updatePayment(@PathVariable String orderNo,
                                                         @RequestBody SettlementPaymentUpdateRequest request) {
        TripOrderSettlement settlement = getByOrderNo(orderNo);
        if (settlement == null) {
            return ResultUtil.error(404, "订单结算记录不存在");
        }
        settlement.setPaymentNo(request.getPaymentNo());
        settlement.setPaymentStatus(request.getPaymentStatus());
        settlement.setPaidAmount(request.getPaidAmount());
        settlement.setPaidAt(request.getPaidAt());
        settlement.setSettlementStatus(request.getSettlementStatus());
        if ("PAID".equals(request.getSettlementStatus())) {
            settlement.setSettledAt(request.getPaidAt() == null ? LocalDateTime.now() : request.getPaidAt());
        }
        settlement.setUpdatedAt(LocalDateTime.now());
        settlementMapper.updateById(settlement);
        return ResultUtil.success(settlement);
    }

    @GetMapping("/{orderNo}")
    public ResponseVo<TripOrderSettlement> get(@PathVariable String orderNo) {
        TripOrderSettlement settlement = getByOrderNo(orderNo);
        if (settlement == null) {
            return ResultUtil.error(404, "订单结算记录不存在");
        }
        return ResultUtil.success(settlement);
    }

    /**
     * 注销账号前检查是否存在未结清订单。
     * 有应付金额时必须支付成功且状态为 PAID；零应付订单允许 PAID/CLOSED。
     */
    @GetMapping("/unsettled-exists")
    public ResponseVo<UnsettledOrderCheckResult> unsettledExists(@RequestParam("passengerId") Long passengerId) {
        if (passengerId == null || passengerId <= 0) {
            return ResultUtil.error(400, "passengerId不能为空");
        }
        Long count = settlementMapper.selectCount(Wrappers.<TripOrderSettlement>lambdaQuery()
                .eq(TripOrderSettlement::getPassengerId, passengerId)
                .apply("((COALESCE(payable_amount, 0) > 0 " +
                        "AND (payment_status <> 2 OR paid_amount IS NULL OR paid_amount < payable_amount OR settlement_status <> 'PAID')) " +
                        "OR (COALESCE(payable_amount, 0) = 0 AND settlement_status NOT IN ('PAID', 'CLOSED')))")
                .last("LIMIT 1"));
        long n = count == null ? 0L : count;
        return ResultUtil.success(new UnsettledOrderCheckResult(n > 0, n));
    }

    private TripOrderSettlement getByOrderNo(String orderNo) {
        return settlementMapper.selectOne(Wrappers.<TripOrderSettlement>lambdaQuery()
                .eq(TripOrderSettlement::getOrderNo, orderNo)
                .last("LIMIT 1"));
    }

    private BigDecimal defaultAmount(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private BigDecimal scale(BigDecimal amount) {
        return defaultAmount(amount).setScale(2, RoundingMode.HALF_UP);
    }

    private String defaultStatus(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
