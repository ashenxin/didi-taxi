package com.sx.passengerapi.model.wallet;

public class CouponInvalidateRequest {
    private Long passengerId;
    private String reason;

    public CouponInvalidateRequest() {
    }

    public CouponInvalidateRequest(Long passengerId, String reason) {
        this.passengerId = passengerId;
        this.reason = reason;
    }

    public Long getPassengerId() {
        return passengerId;
    }

    public void setPassengerId(Long passengerId) {
        this.passengerId = passengerId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}
