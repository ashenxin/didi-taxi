package com.sx.passenger.app.dto;

public class AppAccountCancelSmsSendResult {
    private String mockCode;
    private String maskedPhone;
    private Long lifecycleVersion;

    public String getMockCode() {
        return mockCode;
    }

    public void setMockCode(String mockCode) {
        this.mockCode = mockCode;
    }

    public String getMaskedPhone() {
        return maskedPhone;
    }

    public void setMaskedPhone(String maskedPhone) {
        this.maskedPhone = maskedPhone;
    }

    public Long getLifecycleVersion() {
        return lifecycleVersion;
    }

    public void setLifecycleVersion(Long lifecycleVersion) {
        this.lifecycleVersion = lifecycleVersion;
    }
}
