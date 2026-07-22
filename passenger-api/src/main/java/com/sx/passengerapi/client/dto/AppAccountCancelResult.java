package com.sx.passengerapi.client.dto;

public class AppAccountCancelResult implements PassengerLifecycleResult {
    private Boolean cancelled;
    private Boolean requireLogin;
    private Long customerId;
    private Long newAuthEpoch;
    private String revocationReason;

    public Boolean getCancelled() {
        return cancelled;
    }

    public void setCancelled(Boolean cancelled) {
        this.cancelled = cancelled;
    }

    public Boolean getRequireLogin() {
        return requireLogin;
    }

    public void setRequireLogin(Boolean requireLogin) {
        this.requireLogin = requireLogin;
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
