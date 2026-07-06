package com.sx.passengerapi.model.ordercore;

public class UnsettledOrderCheckResult {
    private Boolean exists;
    private Long count;

    public Boolean getExists() {
        return exists;
    }

    public void setExists(Boolean exists) {
        this.exists = exists;
    }

    public Long getCount() {
        return count;
    }

    public void setCount(Long count) {
        this.count = count;
    }
}
