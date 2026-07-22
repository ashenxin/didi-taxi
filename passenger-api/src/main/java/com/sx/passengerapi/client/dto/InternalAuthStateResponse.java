package com.sx.passengerapi.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class InternalAuthStateResponse {

    private long customerId;
    private Integer businessStatus;
    private String lifecycleStatus;
    private long authEpoch;
    private String currentLifecycleOperationNo;
    private String allowedScope;
    private boolean allowed;

    public InternalAuthStateResponse() {
    }

    public InternalAuthStateResponse(long customerId,
                                     Integer businessStatus,
                                     String lifecycleStatus,
                                     long authEpoch,
                                     String currentLifecycleOperationNo,
                                     String allowedScope,
                                     boolean allowed) {
        this.customerId = customerId;
        this.businessStatus = businessStatus;
        this.lifecycleStatus = lifecycleStatus;
        this.authEpoch = authEpoch;
        this.currentLifecycleOperationNo = currentLifecycleOperationNo;
        this.allowedScope = allowedScope;
        this.allowed = allowed;
    }

    public long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(long customerId) {
        this.customerId = customerId;
    }

    public Integer getBusinessStatus() {
        return businessStatus;
    }

    public void setBusinessStatus(Integer businessStatus) {
        this.businessStatus = businessStatus;
    }

    public String getLifecycleStatus() {
        return lifecycleStatus;
    }

    public void setLifecycleStatus(String lifecycleStatus) {
        this.lifecycleStatus = lifecycleStatus;
    }

    public long getAuthEpoch() {
        return authEpoch;
    }

    public void setAuthEpoch(long authEpoch) {
        this.authEpoch = authEpoch;
    }

    public String getCurrentLifecycleOperationNo() {
        return currentLifecycleOperationNo;
    }

    public void setCurrentLifecycleOperationNo(String currentLifecycleOperationNo) {
        this.currentLifecycleOperationNo = currentLifecycleOperationNo;
    }

    public String getAllowedScope() {
        return allowedScope;
    }

    public void setAllowedScope(String allowedScope) {
        this.allowedScope = allowedScope;
    }

    public boolean isAllowed() {
        return allowed;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
    }
}
