package com.sx.order.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.order.common.util.ResultUtil;
import com.sx.order.common.vo.ResponseVo;
import com.sx.order.dao.TripOrderSettlementMapper;
import com.sx.order.model.TripOrderSettlement;
import com.sx.order.model.dto.SettlementUpsertRequest;
import com.sx.order.model.dto.UnsettledOrderCheckResult;
import com.sx.order.model.dto.BlockingOrderResult;
import com.sx.order.model.dto.ManualPaymentCommand;
import com.sx.order.model.dto.PaymentAttemptResult;
import com.sx.order.model.dto.SettlementSummaryResult;
import com.sx.order.service.SettlementAmountValidator;
import com.sx.order.service.TripOrderWriteService;
import com.sx.order.service.TripOrderSettlementService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单结算内部接口：维护权威结算快照，并提供未结清阻塞、乘客账单和主动支付编排能力。
 * 统一前缀：{@code /api/v1/orders/internal/settlements}；主要供结算任务与 {@code passenger-api} 调用。
 */
@RestController
@RequestMapping("/api/v1/orders/internal/settlements")
public class TripOrderSettlementController {
    private final TripOrderSettlementMapper settlementMapper;
    private final SettlementAmountValidator amountValidator;
    private final TripOrderWriteService orderWriteService;
    private final TripOrderSettlementService settlementService;

    public TripOrderSettlementController(TripOrderSettlementMapper settlementMapper,
                                         SettlementAmountValidator amountValidator,
                                         TripOrderWriteService orderWriteService,
                                         TripOrderSettlementService settlementService) {
        this.settlementMapper = settlementMapper;
        this.amountValidator = amountValidator;
        this.orderWriteService = orderWriteService;
        this.settlementService = settlementService;
    }

    /**
     * 新增或更新订单结算快照，并统一校验原价、优惠、应付金额及收入分配金额。
     * {@code POST /api/v1/orders/internal/settlements}
     */
    @PostMapping
    public ResponseVo<TripOrderSettlement> upsert(@Valid @RequestBody SettlementUpsertRequest request) {
        SettlementAmountValidator.ValidatedAmounts amounts = amountValidator.validate(
                request.getFinalAmount(), request.getCouponDiscountAmount(), request.getPayableAmount());
        SettlementAmountValidator.ValidatedDistribution distribution = amountValidator.validateDistribution(
                amounts.payableAmount(), request.getPlatformServiceFeeRate(),
                request.getPlatformServiceFeeAmount(), request.getCarrierIncomeAmount());
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
        settlement.setFinalAmount(amounts.finalAmount());
        settlement.setCouponId(request.getCouponId());
        settlement.setCouponTemplateId(request.getCouponTemplateId());
        settlement.setCouponCompanyId(request.getCouponCompanyId());
        settlement.setCouponCompanyNo(request.getCouponCompanyNo());
        settlement.setCouponCompanyNameSnapshot(request.getCouponCompanyNameSnapshot());
        settlement.setCouponTeamIdSnapshot(request.getCouponTeamIdSnapshot());
        settlement.setCouponTeamNameSnapshot(request.getCouponTeamNameSnapshot());
        settlement.setCouponType(request.getCouponType());
        settlement.setCouponDiscountAmount(amounts.discountAmount());
        settlement.setCouponRuleSnapshot(request.getCouponRuleSnapshot());
        BigDecimal payableAmount = amounts.payableAmount();
        settlement.setPayableAmount(payableAmount);
        settlement.setPlatformServiceFeeRate(distribution.platformServiceFeeRate());
        settlement.setPlatformServiceFeeAmount(distribution.platformServiceFeeAmount());
        settlement.setCarrierIncomeAmount(distribution.carrierIncomeAmount());
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

    /**
     * 按订单号查询权威结算记录，不存在时返回业务码 404。
     * {@code GET /api/v1/orders/internal/settlements/{orderNo}}
     */
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
     * {@code GET /api/v1/orders/internal/settlements/unsettled-exists?passengerId=}
     */
    @GetMapping("/unsettled-exists")
    public ResponseVo<UnsettledOrderCheckResult> unsettledExists(@RequestParam("passengerId") Long passengerId) {
        if (passengerId == null || passengerId <= 0) {
            return ResultUtil.error(400, "passengerId不能为空");
        }
        Long count = settlementMapper.countPassengerUnsettledOrders(passengerId);
        long n = count == null ? 0L : count;
        return ResultUtil.success(new UnsettledOrderCheckResult(n > 0, n));
    }

    /**
     * 下单预检：查询会阻塞乘客创建新订单的进行中或未结清订单；幂等重放订单不作为阻塞项。
     * {@code GET /api/v1/orders/internal/settlements/blocking-order?passengerId=}
     */
    @GetMapping("/blocking-order")
    public ResponseVo<BlockingOrderResult> blockingOrder(
            @RequestParam("passengerId") Long passengerId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return ResultUtil.success(orderWriteService.findBlockingOrder(passengerId, idempotencyKey));
    }

    /**
     * 查询乘客可见的订单结算详情，并校验订单归属。
     * {@code GET /api/v1/orders/internal/settlements/{orderNo}/passenger?passengerId=}
     */
    @GetMapping("/{orderNo}/passenger")
    public ResponseVo<TripOrderSettlement> passengerSettlement(
            @PathVariable String orderNo,
            @RequestParam("passengerId") Long passengerId) {
        return ResultUtil.success(settlementService.getPassengerSettlement(orderNo, passengerId));
    }

    /**
     * 为未结清订单创建主动支付尝试；{@code Idempotency-Key} 用于单次支付请求幂等。
     * {@code POST /api/v1/orders/internal/settlements/{orderNo}/payments?passengerId=}
     */
    @PostMapping("/{orderNo}/payments")
    public ResponseVo<PaymentAttemptResult> createManualPayment(
            @PathVariable String orderNo,
            @RequestParam("passengerId") Long passengerId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody ManualPaymentCommand command) {
        return ResultUtil.success(settlementService.createManualPayment(
                orderNo, passengerId, idempotencyKey, command));
    }

    /**
     * 批量查询订单结算摘要，供订单列表补充支付与结算状态。
     * {@code POST /api/v1/orders/internal/settlements/batch-query}
     */
    @PostMapping("/batch-query")
    public ResponseVo<List<SettlementSummaryResult>> batchQuery(@RequestBody List<String> orderNos) {
        return ResultUtil.success(settlementService.getSettlementSummaries(orderNos));
    }

    private TripOrderSettlement getByOrderNo(String orderNo) {
        return settlementMapper.selectOne(Wrappers.<TripOrderSettlement>lambdaQuery()
                .eq(TripOrderSettlement::getOrderNo, orderNo)
                .last("LIMIT 1"));
    }

    private String defaultStatus(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
