package com.sx.passengerapi.service;

import com.sx.passengerapi.client.CalculateClient;
import com.sx.passengerapi.client.OrderClient;
import com.sx.passengerapi.client.dto.AppAccountCancelConfirmRequest;
import com.sx.passengerapi.client.dto.AppAccountCancelResult;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.benefit.BenefitClearPointsRequest;
import com.sx.passengerapi.model.ordercore.OrderPageData;
import com.sx.passengerapi.model.ordercore.TripOrderRow;
import com.sx.passengerapi.model.ordercore.UnsettledOrderCheckResult;
import com.sx.passengerapi.model.settings.AccountCancelConfirmRequest;
import com.sx.passengerapi.model.settings.AccountCancelResultVO;
import com.sx.passengerapi.model.wallet.CouponInvalidateRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * P7 灰度期临时保留的旧注销编排。
 *
 * <p>该类只用于未命中灰度的 settings 请求；达到 100% 且观察稳定后整体删除。
 */
@Component
@Slf4j
public class LegacyAccountCancellationAdapter {
    private final OrderClient orderClient;
    private final CalculateClient calculateClient;
    private final PassengerLifecycleOrchestrator lifecycleOrchestrator;
    private final OrderLifecycleShadowPrecheckService orderLifecycleShadow;

    public LegacyAccountCancellationAdapter(
            OrderClient orderClient,
            CalculateClient calculateClient,
            PassengerLifecycleOrchestrator lifecycleOrchestrator,
            OrderLifecycleShadowPrecheckService orderLifecycleShadow) {
        this.orderClient = orderClient;
        this.calculateClient = calculateClient;
        this.lifecycleOrchestrator = lifecycleOrchestrator;
        this.orderLifecycleShadow = orderLifecycleShadow;
    }

    public AccountCancelResultVO confirm(long customerId, AccountCancelConfirmRequest request) {
        LegacyOrderRiskDecision legacyOrderRisk = legacyOrderRisk(customerId);
        try {
            orderLifecycleShadow.compare(customerId, legacyOrderRisk);
        } catch (RuntimeException failure) {
            log.warn("Order生命周期影子预检异常，继续沿用旧注销裁决 customerId={}", customerId);
        }
        if (legacyOrderRisk == LegacyOrderRiskDecision.ACTIVE_ORDER) {
            throw new BizErrorException(409, "当前存在进行中订单，请先完成或取消订单后再注销");
        }
        if (legacyOrderRisk == LegacyOrderRiskDecision.UNSETTLED_ORDER) {
            throw new BizErrorException(409, "当前存在未结清订单，请结清后再注销");
        }
        if (hasLockedCoupon(customerId)) {
            throw new BizErrorException(409, "当前存在订单锁定中的优惠券，请先完成或取消相关订单后再注销");
        }
        AppAccountCancelResult data = lifecycleOrchestrator.confirmAccountCancel(
                new AppAccountCancelConfirmRequest(customerId, request.getCode(), request.getConfirm()));
        tryInvalidateUnusedCoupons(customerId);
        tryClearBenefitPoints(customerId);

        AccountCancelResultVO result = new AccountCancelResultVO();
        result.setCancelled(data.getCancelled());
        result.setRequireLogin(data.getRequireLogin());
        return result;
    }

    private LegacyOrderRiskDecision legacyOrderRisk(long customerId) {
        if (hasActiveOrder(customerId)) {
            return LegacyOrderRiskDecision.ACTIVE_ORDER;
        }
        return hasUnsettledOrder(customerId)
                ? LegacyOrderRiskDecision.UNSETTLED_ORDER
                : LegacyOrderRiskDecision.PASS;
    }

    private boolean hasActiveOrder(long customerId) {
        for (TripOrderRow row : loadAllPassengerOrders(customerId)) {
            Integer status = row.getStatus();
            if (status == null || (status != 5 && status != 6)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasUnsettledOrder(long customerId) {
        ResponseVo<UnsettledOrderCheckResult> response = orderClient.unsettledExists(customerId);
        if (response == null || response.getCode() == null || response.getCode() != 200) {
            throw new BizErrorException(502, "订单结算服务暂时不可用，请稍后重试");
        }
        return response.getData() != null && Boolean.TRUE.equals(response.getData().getExists());
    }

    private boolean hasLockedCoupon(long customerId) {
        ResponseVo<Boolean> response = calculateClient.lockedCouponsExists(customerId);
        if (response == null || response.getCode() == null || response.getCode() != 200) {
            throw new BizErrorException(502, "优惠券服务暂时不可用，请稍后重试");
        }
        return Boolean.TRUE.equals(response.getData());
    }

    private void tryInvalidateUnusedCoupons(long customerId) {
        try {
            ResponseVo<Integer> response = calculateClient.invalidateCouponsByPassenger(
                    new CouponInvalidateRequest(customerId, "ACCOUNT_CANCEL"));
            if (response == null || response.getCode() == null || response.getCode() != 200) {
                log.error("旧注销作废未使用优惠券失败 customerId={} code={}",
                        customerId, response == null ? null : response.getCode());
            }
        } catch (RuntimeException failure) {
            log.error("旧注销作废未使用优惠券异常 customerId={} errorType={}",
                    customerId, failure.getClass().getSimpleName());
        }
    }

    private void tryClearBenefitPoints(long customerId) {
        try {
            ResponseVo<Void> response = calculateClient.clearBenefitPointsByAccountCancel(
                    new BenefitClearPointsRequest(customerId, "settings-cancel-" + customerId));
            if (response == null || response.getCode() == null || response.getCode() != 200) {
                log.error("旧注销清零福利积分失败 customerId={} code={}",
                        customerId, response == null ? null : response.getCode());
            }
        } catch (RuntimeException failure) {
            log.error("旧注销清零福利积分异常 customerId={} errorType={}",
                    customerId, failure.getClass().getSimpleName());
        }
    }

    private List<TripOrderRow> loadAllPassengerOrders(long customerId) {
        ArrayList<TripOrderRow> rows = new ArrayList<>();
        int pageNo = 1;
        while (true) {
            ResponseVo<OrderPageData> response = orderClient.pageOrders(customerId, pageNo, 100);
            if (response == null || response.getCode() == null || response.getCode() != 200) {
                throw new BizErrorException(502, "订单服务暂时不可用，请稍后重试");
            }
            OrderPageData page = response.getData();
            if (page == null || page.getList() == null || page.getList().isEmpty()) {
                break;
            }
            rows.addAll(page.getList());
            if (page.getTotal() != null && rows.size() >= page.getTotal()) {
                break;
            }
            pageNo++;
        }
        return rows.isEmpty() ? Collections.emptyList() : rows;
    }
}
