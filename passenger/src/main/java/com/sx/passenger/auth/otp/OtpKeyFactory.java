package com.sx.passenger.auth.otp;

import java.util.Objects;

import org.springframework.stereotype.Component;

@Component
public final class OtpKeyFactory {

    public String key(OtpPurpose purpose, OtpSubject subject) {
        Objects.requireNonNull(purpose, "purpose must not be null");
        Objects.requireNonNull(subject, "subject must not be null");
        return switch (purpose) {
            case LOGIN -> "app:otp:v2:LOGIN:" + requiredPhoneOnly(subject);
            case PHONE_CHANGE_NEW_PHONE -> "app:otp:v2:PHONE_CHANGE_NEW_PHONE:"
                    + requiredId(subject) + ":" + requiredPhone(subject) + ":" + requiredVersion(subject);
            case ACCOUNT_CANCEL -> "app:otp:v2:ACCOUNT_CANCEL:"
                    + requiredIdWithoutPhone(subject) + ":" + requiredVersion(subject);
        };
    }

    private static String requiredPhoneOnly(OtpSubject subject) {
        if (subject.customerId() != null || subject.lifecycleVersion() != null) {
            throw new IllegalArgumentException("LOGIN OTP subject may only contain phone");
        }
        return requiredPhone(subject);
    }

    private static String requiredPhone(OtpSubject subject) {
        if (subject.phone() == null || subject.phone().isBlank()) {
            throw new IllegalArgumentException("OTP subject requires phone");
        }
        return subject.phone();
    }

    private static Long requiredId(OtpSubject subject) {
        if (subject.customerId() == null || subject.customerId() <= 0) {
            throw new IllegalArgumentException("OTP subject requires positive customerId");
        }
        return subject.customerId();
    }

    private static Long requiredIdWithoutPhone(OtpSubject subject) {
        if (subject.phone() != null) {
            throw new IllegalArgumentException("ACCOUNT_CANCEL OTP subject must not contain phone");
        }
        return requiredId(subject);
    }

    private static Long requiredVersion(OtpSubject subject) {
        if (subject.lifecycleVersion() == null || subject.lifecycleVersion() < 0) {
            throw new IllegalArgumentException("OTP subject requires non-negative lifecycleVersion");
        }
        return subject.lifecycleVersion();
    }
}
