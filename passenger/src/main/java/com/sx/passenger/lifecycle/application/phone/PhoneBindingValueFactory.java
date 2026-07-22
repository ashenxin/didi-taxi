package com.sx.passenger.lifecycle.application.phone;

import com.sx.passenger.lifecycle.persistence.entity.CustomerPhoneBindingHistoryEntity;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

@Component
public final class PhoneBindingValueFactory {
    private static final String CURRENT_HASH_KEY_VERSION = "legacy-v1";

    public CustomerPhoneBindingHistoryEntity active(long customerId, long bindingVersion, String phone,
                                                     String operationNo, LocalDateTime now) {
        return new CustomerPhoneBindingHistoryEntity()
                .setCustomerId(customerId).setBindingVersion(bindingVersion).setStatus("ACTIVE")
                .setPhoneCiphertext(phone.getBytes(StandardCharsets.UTF_8))
                .setPhoneIdentityHash(identityHash(phone)).setHashKeyVersion(CURRENT_HASH_KEY_VERSION)
                .setChangeOperationNo(operationNo).setChangeReason("PHONE_CHANGE")
                .setValidFrom(now).setCreatedAt(now).setUpdatedAt(now);
    }

    String identityHash(String phone) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(phone.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
