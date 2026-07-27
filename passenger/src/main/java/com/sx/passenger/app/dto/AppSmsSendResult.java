package com.sx.passenger.app.dto;

public class AppSmsSendResult {

    private String mockCode;
    private Long lifecycleVersion;

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

    public Long getLifecycleVersion() {
        return lifecycleVersion;
    }

    public void setLifecycleVersion(Long lifecycleVersion) {
        this.lifecycleVersion = lifecycleVersion;
    }
}
