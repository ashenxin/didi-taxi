package com.sx.passengerapi.service;

import com.sx.passengerapi.client.CalculateClient;
import com.sx.passengerapi.client.OrderClient;
import com.sx.passengerapi.client.PassengerCoreSettingsClient;
import com.sx.passengerapi.client.dto.AppAccountCancelConfirmRequest;
import com.sx.passengerapi.client.dto.AppAccountCancelResult;
import com.sx.passengerapi.client.dto.AppAccountCancelSmsSendResult;
import com.sx.passengerapi.client.dto.AppPhoneChangeConfirmRequest;
import com.sx.passengerapi.client.dto.AppPhoneChangeResult;
import com.sx.passengerapi.client.dto.AppPhoneChangeSmsSendRequest;
import com.sx.passengerapi.client.dto.AppSettingsCustomerIdRequest;
import com.sx.passengerapi.client.dto.AppSettingsProfileResponse;
import com.sx.passengerapi.client.dto.AppSmsSendResult;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.ordercore.OrderPageData;
import com.sx.passengerapi.model.ordercore.TripOrderRow;
import com.sx.passengerapi.model.benefit.BenefitClearPointsRequest;
import com.sx.passengerapi.model.ordercore.UnsettledOrderCheckResult;
import com.sx.passengerapi.model.settings.AccountCancelConfirmRequest;
import com.sx.passengerapi.model.settings.AccountCancelResultVO;
import com.sx.passengerapi.model.settings.PhoneChangeConfirmRequest;
import com.sx.passengerapi.model.settings.PhoneChangeResultVO;
import com.sx.passengerapi.model.settings.PhoneChangeSmsSendRequest;
import com.sx.passengerapi.model.settings.SettingsProfileVO;
import com.sx.passengerapi.model.settings.SettingsSmsSendResultVO;
import com.sx.passengerapi.model.wallet.CouponInvalidateRequest;
import com.sx.passengerapi.ws.PassengerWsSessionRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
@Slf4j
public class PassengerSettingsService {
    private final PassengerCoreSettingsClient passengerCoreSettingsClient;
    private final OrderClient orderClient;
    private final CalculateClient calculateClient;
    private final PassengerWsSessionRegistry sessions;

    public PassengerSettingsService(
            PassengerCoreSettingsClient passengerCoreSettingsClient,
            OrderClient orderClient,
            CalculateClient calculateClient,
            PassengerWsSessionRegistry sessions) {
        this.passengerCoreSettingsClient = passengerCoreSettingsClient;
        this.orderClient = orderClient;
        this.calculateClient = calculateClient;
        this.sessions = sessions;
    }

    public SettingsProfileVO profile(long customerId) {
        AppSettingsProfileResponse data = unwrap(passengerCoreSettingsClient.profile(new AppSettingsCustomerIdRequest(customerId)));
        SettingsProfileVO out = new SettingsProfileVO();
        out.setCustomerId(data.getCustomerId());
        out.setMaskedPhone(data.getMaskedPhone());
        out.setStatus(data.getStatus());
        out.setDeleted(data.getDeleted());
        return out;
    }

    public SettingsSmsSendResultVO sendPhoneChangeSms(long customerId, PhoneChangeSmsSendRequest req) {
        AppSmsSendResult data = unwrap(passengerCoreSettingsClient.sendPhoneChangeSms(
                new AppPhoneChangeSmsSendRequest(customerId, req.getNewPhone())));
        SettingsSmsSendResultVO out = new SettingsSmsSendResultVO();
        out.setMockCode(data == null ? null : data.getMockCode());
        return out;
    }

    public PhoneChangeResultVO confirmPhoneChange(long customerId, PhoneChangeConfirmRequest req) {
        AppPhoneChangeResult data = unwrap(passengerCoreSettingsClient.confirmPhoneChange(
                new AppPhoneChangeConfirmRequest(customerId, req.getNewPhone(), req.getCode())));
        sessions.closeCustomerSessions(customerId, "phone_changed");

        PhoneChangeResultVO out = new PhoneChangeResultVO();
        out.setChanged(data.getChanged());
        out.setRequireLogin(data.getRequireLogin());
        out.setMaskedNewPhone(data.getMaskedNewPhone());
        log.info("乘客 BFF 更换手机号完成 customerId={}", customerId);
        return out;
    }

    public SettingsSmsSendResultVO sendAccountCancelSms(long customerId) {
        AppAccountCancelSmsSendResult data = unwrap(passengerCoreSettingsClient.sendAccountCancelSms(
                new AppSettingsCustomerIdRequest(customerId)));
        SettingsSmsSendResultVO out = new SettingsSmsSendResultVO();
        out.setMockCode(data.getMockCode());
        out.setMaskedPhone(data.getMaskedPhone());
        return out;
    }

    public AccountCancelResultVO confirmAccountCancel(long customerId, AccountCancelConfirmRequest req) {
        if (hasActiveOrder(customerId)) {
            throw new BizErrorException(409, "当前存在进行中订单，请先完成或取消订单后再注销");
        }
        if (hasUnsettledOrder(customerId)) {
            throw new BizErrorException(409, "当前存在未结清订单，请结清后再注销");
        }
        if (hasLockedCoupon(customerId)) {
            throw new BizErrorException(409, "当前存在订单锁定中的优惠券，请先完成或取消相关订单后再注销");
        }
        AppAccountCancelResult data = unwrap(passengerCoreSettingsClient.confirmAccountCancel(
                new AppAccountCancelConfirmRequest(customerId, req.getCode(), req.getConfirm())));
        sessions.closeCustomerSessions(customerId, "account_cancelled");
        tryInvalidateUnusedCoupons(customerId);
        tryClearBenefitPoints(customerId);

        AccountCancelResultVO out = new AccountCancelResultVO();
        out.setCancelled(data.getCancelled());
        out.setRequireLogin(data.getRequireLogin());
        log.info("乘客 BFF 注销账号完成 customerId={}", customerId);
        return out;
    }

    private boolean hasActiveOrder(long customerId) {
        for (TripOrderRow row : loadAllPassengerOrders(customerId)) {
            Integer st = row.getStatus();
            if (st == null) {
                return true;
            }
            // 只有已完成和已取消允许注销；未知状态按有风险处理。
            if (st != 5 && st != 6) {
                return true;
            }
        }
        return false;
    }

    private boolean hasUnsettledOrder(long customerId) {
        ResponseVo<UnsettledOrderCheckResult> resp = orderClient.unsettledExists(customerId);
        if (resp == null || resp.getCode() == null || resp.getCode() != 200) {
            log.warn("注销前查询未结清订单失败 passengerId={} code={} msg={}",
                    customerId, resp == null ? null : resp.getCode(), resp == null ? null : resp.getMsg());
            throw new BizErrorException(502, "订单结算服务暂时不可用，请稍后重试");
        }
        UnsettledOrderCheckResult data = resp.getData();
        return data != null && Boolean.TRUE.equals(data.getExists());
    }

    private boolean hasLockedCoupon(long customerId) {
        ResponseVo<Boolean> resp = calculateClient.lockedCouponsExists(customerId);
        if (resp == null || resp.getCode() == null || resp.getCode() != 200) {
            log.warn("注销前查询锁定优惠券失败 passengerId={} code={} msg={}",
                    customerId, resp == null ? null : resp.getCode(), resp == null ? null : resp.getMsg());
            throw new BizErrorException(502, "优惠券服务暂时不可用，请稍后重试");
        }
        return Boolean.TRUE.equals(resp.getData());
    }

    private void tryInvalidateUnusedCoupons(long customerId) {
        try {
            ResponseVo<Integer> resp = calculateClient.invalidateCouponsByPassenger(
                    new CouponInvalidateRequest(customerId, "ACCOUNT_CANCEL"));
            if (resp == null || resp.getCode() == null || resp.getCode() != 200) {
                log.error("注销后作废未使用优惠券失败，账号注销结果仍返回成功 passengerId={} code={} msg={}",
                        customerId, resp == null ? null : resp.getCode(), resp == null ? null : resp.getMsg());
                return;
            }
            log.info("注销后作废未使用优惠券完成 passengerId={} count={}", customerId, resp.getData());
        } catch (RuntimeException ex) {
            log.error("注销后作废未使用优惠券异常，账号注销结果仍返回成功 passengerId={}", customerId, ex);
        }
    }

    private void tryClearBenefitPoints(long customerId) {
        try {
            ResponseVo<Void> resp = calculateClient.clearBenefitPointsByAccountCancel(
                    new BenefitClearPointsRequest(customerId, "settings-cancel-" + customerId));
            if (resp == null || resp.getCode() == null || resp.getCode() != 200) {
                log.error("注销后清零福利积分失败，账号注销结果仍返回成功 passengerId={} code={} msg={}",
                        customerId, resp == null ? null : resp.getCode(), resp == null ? null : resp.getMsg());
                return;
            }
            log.info("注销后清零福利积分完成 passengerId={}", customerId);
        } catch (RuntimeException ex) {
            log.error("注销后清零福利积分异常，账号注销结果仍返回成功 passengerId={}", customerId, ex);
        }
    }

    private List<TripOrderRow> loadAllPassengerOrders(Long passengerId) {
        java.util.ArrayList<TripOrderRow> rows = new java.util.ArrayList<>();
        final int pageSize = 100;
        int pageNo = 1;
        while (true) {
            ResponseVo<OrderPageData> resp = orderClient.pageOrders(passengerId, pageNo, pageSize);
            if (resp == null || resp.getCode() == null || resp.getCode() != 200) {
                log.warn("注销前查询订单失败 passengerId={} pageNo={} code={} msg={}",
                        passengerId, pageNo, resp == null ? null : resp.getCode(), resp == null ? null : resp.getMsg());
                throw new BizErrorException(502, "订单服务暂时不可用，请稍后重试");
            }
            OrderPageData data = resp.getData();
            if (data == null || data.getList() == null || data.getList().isEmpty()) {
                break;
            }
            rows.addAll(data.getList());
            Integer total = data.getTotal();
            if (total != null && rows.size() >= total) {
                break;
            }
            pageNo++;
        }
        return rows.isEmpty() ? Collections.emptyList() : rows;
    }

    private static <T> T unwrap(ResponseVo<T> body) {
        if (body == null || body.getCode() == null) {
            throw new BizErrorException(502, "服务暂时不可用，请稍后重试");
        }
        if (body.getCode() != 200) {
            throw new BizErrorException(body.getCode(), body.getMsg());
        }
        return body.getData();
    }
}
