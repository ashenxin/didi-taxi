package com.sx.passenger.lifecycle.application.phone;

import com.sx.passenger.lifecycle.persistence.entity.CustomerPhoneBindingHistoryEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 创建 ACTIVE 手机号绑定历史记录。
 *
 * <p>当前沿用 legacy-v1 存储方案；该类把密文/摘要产生方式收束在一处，
 * 便于后续升级加密和摘要密钥，而不把处理逻辑散落到换号服务。
 */
@Component
public final class PhoneBindingValueFactory {
    private static final String CURRENT_HASH_KEY_VERSION = "legacy-v1";

    /** 组装新注册账号的首条手机号绑定记录。 */
    public CustomerPhoneBindingHistoryEntity initialRegistration(
            long customerId, String phone, LocalDateTime now) {
        return active(customerId, 1L, phone, null, "REGISTER", now);
    }

    /** 组装一条从 now 开始生效的新手机号绑定记录。 */
    public CustomerPhoneBindingHistoryEntity active(long customerId, long bindingVersion, String phone,
                                                     String operationNo, LocalDateTime now) {
        return active(customerId, bindingVersion, phone, operationNo, "PHONE_CHANGE", now);
    }

    private CustomerPhoneBindingHistoryEntity active(
            long customerId, long bindingVersion, String phone, String operationNo,
            String changeReason, LocalDateTime now) {
        return new CustomerPhoneBindingHistoryEntity()
                .setCustomerId(customerId).setBindingVersion(bindingVersion).setStatus("ACTIVE")
                .setPhoneCiphertext(phone.getBytes(StandardCharsets.UTF_8))
                .setPhoneIdentityHash(identityHash(phone)).setHashKeyVersion(CURRENT_HASH_KEY_VERSION)
                .setChangeOperationNo(operationNo).setChangeReason(changeReason)
                .setValidFrom(now).setCreatedAt(now).setUpdatedAt(now);
    }

    /** 生成手机号等值身份摘要；不会作为原文展示。 */
    String identityHash(String phone) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(phone.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
