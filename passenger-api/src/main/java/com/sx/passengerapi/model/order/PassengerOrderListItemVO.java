package com.sx.passengerapi.model.order;

import java.math.BigDecimal;
import java.util.List;

/**
 * 乘客端“我的订单”列表卡片。
 */
public class PassengerOrderListItemVO {

    private String orderNo;
    private String originAddress;
    private String destAddress;
    private OrderStatus status;
    private BigDecimal estimatedAmount;
    private BigDecimal finalAmount;
    private PassengerOrderDriverVO driver;
    private PassengerOrderTimestamps timestamps;
    private Integer cancelBy;
    private String cancelReason;
    private Boolean reDispatching;
    private List<PassengerOrderActionVO> actions;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getOriginAddress() {
        return originAddress;
    }

    public void setOriginAddress(String originAddress) {
        this.originAddress = originAddress;
    }

    public String getDestAddress() {
        return destAddress;
    }

    public void setDestAddress(String destAddress) {
        this.destAddress = destAddress;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public BigDecimal getEstimatedAmount() {
        return estimatedAmount;
    }

    public void setEstimatedAmount(BigDecimal estimatedAmount) {
        this.estimatedAmount = estimatedAmount;
    }

    public BigDecimal getFinalAmount() {
        return finalAmount;
    }

    public void setFinalAmount(BigDecimal finalAmount) {
        this.finalAmount = finalAmount;
    }

    public PassengerOrderDriverVO getDriver() {
        return driver;
    }

    public void setDriver(PassengerOrderDriverVO driver) {
        this.driver = driver;
    }

    public PassengerOrderTimestamps getTimestamps() {
        return timestamps;
    }

    public void setTimestamps(PassengerOrderTimestamps timestamps) {
        this.timestamps = timestamps;
    }

    public Integer getCancelBy() {
        return cancelBy;
    }

    public void setCancelBy(Integer cancelBy) {
        this.cancelBy = cancelBy;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public Boolean getReDispatching() {
        return reDispatching;
    }

    public void setReDispatching(Boolean reDispatching) {
        this.reDispatching = reDispatching;
    }

    public List<PassengerOrderActionVO> getActions() {
        return actions;
    }

    public void setActions(List<PassengerOrderActionVO> actions) {
        this.actions = actions;
    }
}
