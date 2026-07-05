package com.sx.passengerapi.client.dto;

public class AppAccountCancelResult {
    private Boolean cancelled;
    private Boolean requireLogin;

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
}
