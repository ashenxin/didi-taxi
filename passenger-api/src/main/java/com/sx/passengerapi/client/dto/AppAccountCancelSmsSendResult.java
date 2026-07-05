package com.sx.passengerapi.client.dto;

public class AppAccountCancelSmsSendResult {
    private String mockCode;
    private String maskedPhone;

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
}
