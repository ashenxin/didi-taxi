package com.sx.passenger.auth.otp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtpKeyFactoryTest {

    private final OtpKeyFactory keys = new OtpKeyFactory();

    @Test
    void buildsPurposeIsolatedKeys() {
        assertThat(keys.key(OtpPurpose.LOGIN, OtpSubject.login("13800138000")))
                .isEqualTo("app:otp:v2:LOGIN:13800138000");
        assertThat(keys.key(OtpPurpose.PHONE_CHANGE_NEW_PHONE,
                OtpSubject.phoneChange(7L, "13900139000", 12L)))
                .isEqualTo("app:otp:v2:PHONE_CHANGE_NEW_PHONE:7:13900139000:12");
        assertThat(keys.key(OtpPurpose.ACCOUNT_CANCEL, OtpSubject.accountCancel(7L, 12L)))
                .isEqualTo("app:otp:v2:ACCOUNT_CANCEL:7:12");
    }

    @Test
    void rejectsSubjectThatDoesNotMatchPurpose() {
        assertThatThrownBy(() -> keys.key(OtpPurpose.LOGIN, OtpSubject.accountCancel(7L, 1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsInitialLifecycleVersionForSettingsPurposes() {
        assertThat(keys.key(OtpPurpose.PHONE_CHANGE_NEW_PHONE,
                OtpSubject.phoneChange(7L, "13900139000", 0L)))
                .isEqualTo("app:otp:v2:PHONE_CHANGE_NEW_PHONE:7:13900139000:0");
        assertThat(keys.key(OtpPurpose.ACCOUNT_CANCEL, OtpSubject.accountCancel(7L, 0L)))
                .isEqualTo("app:otp:v2:ACCOUNT_CANCEL:7:0");
    }
}
