package com.sx.passengerapi.model.order;

/**
 * 取消订单请求体。
 * <p>{@code passengerId} 由 {@code X-User-Id} 注入，请求体可不传。</p>
 */
public class CancelOrderRequest {

    /** 乘客 ID；服务端从 {@code X-User-Id} 注入。 */
    private Long passengerId;

    private String cancelReason;

    public Long getPassengerId() {
        return passengerId;
    }

    public void setPassengerId(Long passengerId) {
        this.passengerId = passengerId;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }
}
