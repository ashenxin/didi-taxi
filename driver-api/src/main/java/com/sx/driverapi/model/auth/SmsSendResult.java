package com.sx.driverapi.model.auth;

public class SmsSendResult {

    private String mockCode;

    public SmsSendResult() {
    }

    public SmsSendResult(String mockCode) {
        this.mockCode = mockCode;
    }

    public String getMockCode() {
        return mockCode;
    }

    public void setMockCode(String mockCode) {
        this.mockCode = mockCode;
    }
}
