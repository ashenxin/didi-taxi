package com.sx.passengerapi.service;

import com.sx.passengerapi.client.CalculateClient;
import com.sx.passengerapi.client.OrderClient;
import com.sx.passengerapi.client.WalletClient;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.ordercore.TripOrderRow;
import com.sx.passengerapi.model.wallet.AutoPayAgreementVO;
import com.sx.passengerapi.model.wallet.AutoPaySignRequest;
import com.sx.passengerapi.model.wallet.AutoPaySignResult;
import com.sx.passengerapi.model.wallet.CouponPageVO;
import com.sx.passengerapi.model.wallet.CouponClaimResult;
import com.sx.passengerapi.model.wallet.CouponTemplateVO;
import com.sx.passengerapi.model.wallet.CouponVO;
import com.sx.passengerapi.model.wallet.WalletSummaryVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

@Service
public class PassengerWalletService {
    private final WalletClient walletClient;
    private final CalculateClient calculateClient;
    private final OrderClient orderClient;

    public PassengerWalletService(WalletClient walletClient, CalculateClient calculateClient, OrderClient orderClient) {
        this.walletClient = walletClient;
        this.calculateClient = calculateClient;
        this.orderClient = orderClient;
    }

    public WalletSummaryVO summary(long passengerId) {
        List<AutoPayAgreementVO> agreements = unwrap(walletClient.listAgreements(passengerId), "钱包服务调用失败");
        AutoPayAgreementVO defaultAgreement = unwrap(walletClient.defaultAgreement(passengerId), "钱包服务调用失败");
        CouponPageVO coupons = unwrap(calculateClient.pageCoupons(passengerId, "UNUSED", 1, 1), "计价服务调用失败");

        WalletSummaryVO vo = new WalletSummaryVO();
        vo.setActiveAutoPayCount((int) agreements.stream().filter(item -> "ACTIVE".equals(item.getStatus())).count());
        vo.setDefaultAutoPayAgreement(defaultAgreement);
        vo.setAvailableCouponCount(coupons == null ? 0 : coupons.getTotal());
        return vo;
    }

    public List<AutoPayAgreementVO> listAgreements(long passengerId) {
        return unwrap(walletClient.listAgreements(passengerId), "钱包服务调用失败");
    }

    public AutoPaySignResult sign(long passengerId, AutoPaySignRequest request) {
        return unwrap(walletClient.sign(passengerId, request), "钱包服务调用失败");
    }

    public AutoPayAgreementVO setDefault(long passengerId, Long agreementId) {
        return unwrap(walletClient.setDefault(passengerId, agreementId), "钱包服务调用失败");
    }

    public AutoPayAgreementVO close(long passengerId, Long agreementId) {
        return unwrap(walletClient.close(passengerId, agreementId), "钱包服务调用失败");
    }

    public CouponPageVO pageCoupons(long passengerId, String status, Integer pageNo, Integer pageSize) {
        return unwrap(calculateClient.pageCoupons(passengerId, status, pageNo, pageSize), "计价服务调用失败");
    }

    public List<CouponVO> availableCoupons(long passengerId, String orderNo) {
        TripOrderRow order = unwrap(orderClient.getByOrderNo(orderNo), "订单服务调用失败");
        if (order == null) {
            throw new BizErrorException(404, "订单不存在");
        }
        if (order.getPassengerId() == null || order.getPassengerId() != passengerId) {
            throw new BizErrorException(403, "禁止查询他人订单优惠券");
        }
        BigDecimal amount = order.getFinalAmount() != null ? order.getFinalAmount() : order.getEstimatedAmount();
        if (amount == null) {
            amount = BigDecimal.ZERO;
        }
        if (order.getCompanyId() == null || order.getCityCode() == null || order.getProductCode() == null) {
            return Collections.emptyList();
        }
        return unwrap(calculateClient.availableCoupons(passengerId, order.getCompanyId(), amount,
                        order.getCityCode(), order.getProductCode()),
                "计价服务调用失败");
    }

    public List<CouponTemplateVO> claimableCoupons(long passengerId) {
        return unwrap(calculateClient.claimableCoupons(passengerId), "计价服务调用失败");
    }

    public CouponClaimResult claimAllCoupons(long passengerId) {
        return unwrap(calculateClient.claimAllCoupons(passengerId), "计价服务调用失败");
    }

    private <T> T unwrap(ResponseVo<T> response, String fallbackMessage) {
        if (response == null || response.getCode() == null || response.getCode() != 200) {
            int code = response == null || response.getCode() == null ? 502 : response.getCode();
            String msg = response == null || response.getMsg() == null ? fallbackMessage : response.getMsg();
            throw new BizErrorException(code, msg);
        }
        return response.getData();
    }
}
