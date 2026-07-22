package com.sx.passengerapi.model.auth;

import java.io.Serializable;

public class CustomerLoginResponse implements Serializable {

    private String accessToken;
    private String tokenType = "Bearer";
    private long expiresIn;
    private String scope;
    private String operationNo;
    private CustomerProfileVO customer;

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getOperationNo() {
        return operationNo;
    }

    public void setOperationNo(String operationNo) {
        this.operationNo = operationNo;
    }

    public CustomerProfileVO getCustomer() {
        return customer;
    }

    public void setCustomer(CustomerProfileVO customer) {
        this.customer = customer;
    }
}
