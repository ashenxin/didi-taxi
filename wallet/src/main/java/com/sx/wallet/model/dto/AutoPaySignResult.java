package com.sx.wallet.model.dto;

public class AutoPaySignResult {
    private Long agreementId;
    private String channel;
    private String signUrl;
    private boolean mockSigned;

    public Long getAgreementId() {
        return agreementId;
    }

    public void setAgreementId(Long agreementId) {
        this.agreementId = agreementId;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public String getSignUrl() {
        return signUrl;
    }

    public void setSignUrl(String signUrl) {
        this.signUrl = signUrl;
    }

    public boolean isMockSigned() {
        return mockSigned;
    }

    public void setMockSigned(boolean mockSigned) {
        this.mockSigned = mockSigned;
    }
}
