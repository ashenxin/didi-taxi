package com.sx.passengerapi.service;

import com.sx.passengerapi.client.CalculateClient;
import com.sx.passengerapi.client.OrderClient;
import com.sx.passengerapi.client.WalletClient;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.config.CouponClaimIdentityProperties;
import com.sx.passengerapi.model.ordercore.TripOrderRow;
import com.sx.passengerapi.model.wallet.AutoPayAgreementVO;
import com.sx.passengerapi.model.wallet.AutoPaySignRequest;
import com.sx.passengerapi.model.wallet.AutoPaySignResult;
import com.sx.passengerapi.model.wallet.CouponPageVO;
import com.sx.passengerapi.model.wallet.CouponClaimRequest;
import com.sx.passengerapi.model.wallet.CouponClaimResult;
import com.sx.passengerapi.model.wallet.CouponTemplateVO;
import com.sx.passengerapi.model.wallet.CouponVO;
import com.sx.passengerapi.model.wallet.WalletSummaryVO;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.List;

@Service
public class PassengerWalletService {
    private static final String CLAIM_IDENTITY_PHONE = "PHONE";

    private final WalletClient walletClient;
    private final CalculateClient calculateClient;
    private final OrderClient orderClient;
    private final CouponClaimIdentityProperties claimIdentityProperties;

    public PassengerWalletService(WalletClient walletClient,
                                  CalculateClient calculateClient,
                                  OrderClient orderClient,
                                  CouponClaimIdentityProperties claimIdentityProperties) {
        this.walletClient = walletClient;
        this.calculateClient = calculateClient;
        this.orderClient = orderClient;
        this.claimIdentityProperties = claimIdentityProperties;
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

    public List<CouponTemplateVO> claimableCoupons(long passengerId, String phone) {
        return unwrap(calculateClient.claimableCoupons(passengerId, CLAIM_IDENTITY_PHONE, phoneIdentityHash(phone)), "计价服务调用失败");
    }

    public CouponClaimResult claimAllCoupons(long passengerId, String phone) {
        CouponClaimRequest request = new CouponClaimRequest();
        fillClaimIdentity(request, phone);
        return unwrap(calculateClient.claimAllCoupons(passengerId, request), "计价服务调用失败");
    }

    public CouponClaimResult claimCoupons(long passengerId, CouponClaimRequest request, String phone) {
        CouponClaimRequest safeRequest = request == null ? new CouponClaimRequest() : request;
        fillClaimIdentity(safeRequest, phone);
        return unwrap(calculateClient.claimCoupons(passengerId, safeRequest), "计价服务调用失败");
    }

    private void fillClaimIdentity(CouponClaimRequest request, String phone) {
        request.setClaimIdentityType(CLAIM_IDENTITY_PHONE);
        request.setClaimIdentityHash(phoneIdentityHash(phone));
    }

    private String phoneIdentityHash(String phone) {
        if (phone == null || phone.isBlank()) {
            throw new BizErrorException(401, "登录信息已失效，请重新登录");
        }
        String normalizedPhone = phone.trim();
        String secret = claimIdentityProperties.getPhoneHashSecret();
        if (secret == null || secret.isBlank()) {
            throw new BizErrorException(500, "优惠券领取身份密钥未配置");
        }
        try {
            return hmacSha256Hex(secret, normalizedPhone);
        } catch (GeneralSecurityException ex) {
            throw new BizErrorException(500, "优惠券领取身份计算失败");
        }
    }

    static String hmacSha256Hex(String secret, String value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] bytes = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            out.append(String.format("%02x", item));
        }
        return out.toString();
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
