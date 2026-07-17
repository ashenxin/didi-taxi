package com.sx.wallet.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.wallet.dao.WalletAutoPayAgreementMapper;
import com.sx.wallet.model.WalletAutoPayAgreement;
import com.sx.wallet.model.dto.AutoPayAgreementVO;
import com.sx.wallet.model.dto.AutoPayRequest;
import com.sx.wallet.model.dto.AutoPaySignRequest;
import com.sx.wallet.model.dto.AutoPaySignResult;
import com.sx.wallet.model.dto.CreatePaymentAttemptRequest;
import com.sx.wallet.model.dto.PaymentResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class WalletService {
    private static final String STATUS_SIGNING = "SIGNING";
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_CLOSED = "CLOSED";
    private final WalletAutoPayAgreementMapper agreementMapper;
    private final boolean mockEnabled;
    private final PaymentAttemptService paymentAttemptService;

    public WalletService(WalletAutoPayAgreementMapper agreementMapper,
                         @Value("${wallet.payment.mock.enabled:true}") boolean mockEnabled,
                         PaymentAttemptService paymentAttemptService) {
        this.agreementMapper = agreementMapper;
        this.mockEnabled = mockEnabled;
        this.paymentAttemptService = paymentAttemptService;
    }

    public List<AutoPayAgreementVO> listAgreements(Long passengerId) {
        return agreementMapper.selectList(Wrappers.<WalletAutoPayAgreement>lambdaQuery()
                        .eq(WalletAutoPayAgreement::getPassengerId, passengerId)
                        .eq(WalletAutoPayAgreement::getIsDeleted, 0)
                        .orderByDesc(WalletAutoPayAgreement::getIsDefault)
                        .orderByDesc(WalletAutoPayAgreement::getUpdatedAt))
                .stream()
                .map(this::toVO)
                .toList();
    }

    public AutoPayAgreementVO getAgreement(Long passengerId, Long agreementId) {
        WalletAutoPayAgreement agreement = getOwnedAgreement(passengerId, agreementId);
        return agreement == null ? null : toVO(agreement);
    }

    public AutoPayAgreementVO getDefaultAgreement(Long passengerId) {
        WalletAutoPayAgreement agreement = agreementMapper.selectOne(Wrappers.<WalletAutoPayAgreement>lambdaQuery()
                .eq(WalletAutoPayAgreement::getPassengerId, passengerId)
                .eq(WalletAutoPayAgreement::getAgreementStatus, STATUS_ACTIVE)
                .eq(WalletAutoPayAgreement::getIsDefault, 1)
                .eq(WalletAutoPayAgreement::getIsDeleted, 0)
                .last("LIMIT 1"));
        return agreement == null ? null : toVO(agreement);
    }

    @Transactional
    public AutoPaySignResult sign(Long passengerId, AutoPaySignRequest request) {
        String channel = normalizeChannel(request.getChannel());
        LocalDateTime now = LocalDateTime.now();
        WalletAutoPayAgreement agreement = agreementMapper.selectOne(Wrappers.<WalletAutoPayAgreement>lambdaQuery()
                .eq(WalletAutoPayAgreement::getPassengerId, passengerId)
                .eq(WalletAutoPayAgreement::getChannel, channel)
                .eq(WalletAutoPayAgreement::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (agreement == null) {
            agreement = new WalletAutoPayAgreement()
                    .setPassengerId(passengerId)
                    .setChannel(channel)
                    .setAgreementNo(buildAgreementNo(passengerId, channel))
                    .setAgreementStatus(mockEnabled ? STATUS_ACTIVE : STATUS_SIGNING)
                    .setIsDefault(hasActiveAgreement(passengerId) ? 0 : 1)
                    .setSignScene(request.getSignScene())
                    .setSignedAt(mockEnabled ? now : null)
                    .setRawRequest("{}")
                    .setRawResponse("{}")
                    .setCreatedAt(now)
                    .setUpdatedAt(now)
                    .setIsDeleted(0);
            agreementMapper.insert(agreement);
        } else if (!STATUS_ACTIVE.equals(agreement.getAgreementStatus())) {
            agreement.setAgreementNo(buildAgreementNo(passengerId, channel));
            agreement.setAgreementStatus(mockEnabled ? STATUS_ACTIVE : STATUS_SIGNING);
            agreement.setSignScene(request.getSignScene());
            agreement.setSignedAt(mockEnabled ? now : null);
            agreement.setClosedAt(null);
            agreement.setFailReason(null);
            agreement.setUpdatedAt(now);
            agreementMapper.updateById(agreement);
        }

        if (agreement.getIsDefault() == null || agreement.getIsDefault() == 0) {
            ensureDefaultIfMissing(passengerId, agreement);
        }

        AutoPaySignResult result = new AutoPaySignResult();
        result.setAgreementId(agreement.getId());
        result.setChannel(agreement.getChannel());
        result.setSignUrl(mockEnabled ? null : "TODO_CONNECT_CHANNEL_SIGN_URL");
        result.setMockSigned(mockEnabled);
        return result;
    }

    @Transactional
    public AutoPayAgreementVO setDefault(Long passengerId, Long agreementId) {
        WalletAutoPayAgreement agreement = getOwnedAgreement(passengerId, agreementId);
        if (agreement == null) {
            return null;
        }
        if (!STATUS_ACTIVE.equals(agreement.getAgreementStatus())) {
            throw new IllegalArgumentException("只能设置已生效的免密协议为默认");
        }
        agreementMapper.update(null, Wrappers.<WalletAutoPayAgreement>lambdaUpdate()
                .eq(WalletAutoPayAgreement::getPassengerId, passengerId)
                .eq(WalletAutoPayAgreement::getIsDeleted, 0)
                .set(WalletAutoPayAgreement::getIsDefault, 0)
                .set(WalletAutoPayAgreement::getUpdatedAt, LocalDateTime.now()));
        agreement.setIsDefault(1);
        agreement.setUpdatedAt(LocalDateTime.now());
        agreementMapper.updateById(agreement);
        return toVO(agreement);
    }

    @Transactional
    public AutoPayAgreementVO close(Long passengerId, Long agreementId) {
        WalletAutoPayAgreement agreement = getOwnedAgreement(passengerId, agreementId);
        if (agreement == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        agreement.setAgreementStatus(STATUS_CLOSED);
        agreement.setIsDefault(0);
        agreement.setClosedAt(now);
        agreement.setUpdatedAt(now);
        agreementMapper.updateById(agreement);
        return toVO(agreement);
    }

    public PaymentResult autoPay(AutoPayRequest request) {
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("支付金额必须大于0");
        }
        CreatePaymentAttemptRequest attempt = new CreatePaymentAttemptRequest();
        attempt.setOrderNo(request.getOrderNo());
        attempt.setPassengerId(request.getPassengerId());
        attempt.setAmount(request.getAmount());
        attempt.setTriggerType("AUTO_PAY");
        attempt.setIdempotencyKey(request.getIdempotencyKey());
        return paymentAttemptService.create(attempt);
    }

    private WalletAutoPayAgreement getOwnedAgreement(Long passengerId, Long agreementId) {
        if (agreementId == null) {
            return null;
        }
        return agreementMapper.selectOne(Wrappers.<WalletAutoPayAgreement>lambdaQuery()
                .eq(WalletAutoPayAgreement::getId, agreementId)
                .eq(WalletAutoPayAgreement::getPassengerId, passengerId)
                .eq(WalletAutoPayAgreement::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private boolean hasActiveAgreement(Long passengerId) {
        return agreementMapper.selectCount(Wrappers.<WalletAutoPayAgreement>lambdaQuery()
                .eq(WalletAutoPayAgreement::getPassengerId, passengerId)
                .eq(WalletAutoPayAgreement::getAgreementStatus, STATUS_ACTIVE)
                .eq(WalletAutoPayAgreement::getIsDeleted, 0)) > 0;
    }

    private void ensureDefaultIfMissing(Long passengerId, WalletAutoPayAgreement agreement) {
        Long defaultCount = agreementMapper.selectCount(Wrappers.<WalletAutoPayAgreement>lambdaQuery()
                .eq(WalletAutoPayAgreement::getPassengerId, passengerId)
                .eq(WalletAutoPayAgreement::getAgreementStatus, STATUS_ACTIVE)
                .eq(WalletAutoPayAgreement::getIsDefault, 1)
                .eq(WalletAutoPayAgreement::getIsDeleted, 0));
        if (defaultCount == 0 && STATUS_ACTIVE.equals(agreement.getAgreementStatus())) {
            agreement.setIsDefault(1);
            agreement.setUpdatedAt(LocalDateTime.now());
            agreementMapper.updateById(agreement);
        }
    }

    private String normalizeChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            throw new IllegalArgumentException("支付渠道不能为空");
        }
        String normalized = channel.trim().toUpperCase(Locale.ROOT);
        if (!"ALIPAY".equals(normalized) && !"WECHAT".equals(normalized)) {
            throw new IllegalArgumentException("仅支持ALIPAY/WECHAT");
        }
        return normalized;
    }

    private String buildAgreementNo(Long passengerId, String channel) {
        return "MOCK-" + channel + "-" + passengerId + "-" + System.currentTimeMillis();
    }

    private AutoPayAgreementVO toVO(WalletAutoPayAgreement agreement) {
        AutoPayAgreementVO vo = new AutoPayAgreementVO();
        vo.setAgreementId(agreement.getId());
        vo.setChannel(agreement.getChannel());
        vo.setChannelName(channelName(agreement.getChannel()));
        vo.setStatus(agreement.getAgreementStatus());
        vo.setDefaulted(agreement.getIsDefault() != null && agreement.getIsDefault() == 1);
        vo.setSignedAt(agreement.getSignedAt());
        vo.setLastUsedAt(agreement.getLastUsedAt());
        return vo;
    }

    private String channelName(String channel) {
        if ("ALIPAY".equals(channel)) {
            return "支付宝";
        }
        if ("WECHAT".equals(channel)) {
            return "微信";
        }
        return channel;
    }
}
