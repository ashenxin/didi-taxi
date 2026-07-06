package com.sx.order.model.dto;

public class UnsettledOrderCheckResult {
    private Boolean exists;
    private Long count;

    public UnsettledOrderCheckResult() {
    }

    public UnsettledOrderCheckResult(Boolean exists, Long count) {
        this.exists = exists;
        this.count = count;
    }

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
