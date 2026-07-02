package com.sx.passenger.app.dto;

public class AppSmsSendResult {

    private String mockCode;

    public AppSmsSendResult() {
    }

    public AppSmsSendResult(String mockCode) {
        this.mockCode = mockCode;
    }

    public String getMockCode() {
        return mockCode;
    }

    public void setMockCode(String mockCode) {
        this.mockCode = mockCode;
    }
}
