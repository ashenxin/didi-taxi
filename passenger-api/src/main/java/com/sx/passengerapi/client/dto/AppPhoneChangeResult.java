package com.sx.passengerapi.client.dto;

public class AppPhoneChangeResult implements PassengerLifecycleResult {
    private Boolean changed;
    private Boolean requireLogin;
    private String maskedNewPhone;
    private Long customerId;
    private Long newAuthEpoch;
    private String revocationReason;

    public Boolean getChanged() {
        return changed;
    }

    public void setChanged(Boolean changed) {
        this.changed = changed;
    }

    public Boolean getRequireLogin() {
        return requireLogin;
    }

    public void setRequireLogin(Boolean requireLogin) {
        this.requireLogin = requireLogin;
    }

    public String getMaskedNewPhone() {
        return maskedNewPhone;
    }

    public void setMaskedNewPhone(String maskedNewPhone) {
        this.maskedNewPhone = maskedNewPhone;
    }

    @Override
    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    @Override
    public Long getNewAuthEpoch() {
        return newAuthEpoch;
    }

    public void setNewAuthEpoch(Long newAuthEpoch) {
        this.newAuthEpoch = newAuthEpoch;
    }

    @Override
    public String getRevocationReason() {
        return revocationReason;
    }

    public void setRevocationReason(String revocationReason) {
        this.revocationReason = revocationReason;
    }
}
