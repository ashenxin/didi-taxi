package com.sx.passenger.auth.otp;

public record OtpSubject(String phone, Long customerId, Long lifecycleVersion) {

    public static OtpSubject login(String phone) {
        return new OtpSubject(phone, null, null);
    }

    public static OtpSubject phoneChange(long customerId, String phone, long lifecycleVersion) {
        return new OtpSubject(phone, customerId, lifecycleVersion);
    }

    public static OtpSubject accountCancel(long customerId, long lifecycleVersion) {
        return new OtpSubject(null, customerId, lifecycleVersion);
    }
}
