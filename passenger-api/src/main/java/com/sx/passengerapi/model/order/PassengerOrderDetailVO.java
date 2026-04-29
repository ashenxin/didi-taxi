package com.sx.passengerapi.model.order;

import java.math.BigDecimal;

/**
 * 乘客端订单详情（轮询用），在 trip_order 基础上补充 {@link OrderStatus}。
 */
public class PassengerOrderDetailVO {

    private String orderNo;
    private String productCode;
    private String provinceCode;
    private String cityCode;
    private String originAddress;
    private String destAddress;
    private OrderStatus status;
    private BigDecimal estimatedAmount;
    private BigDecimal finalAmount;
    /** 已指派/服务中的司机摘要（无司机时为 null） */
    private PassengerOrderDriverVO driver;
    private PassengerOrderTimestamps timestamps;
    /** 是否处于“司机拒绝/取消后重新派单”阶段 */
    private Boolean reDispatching;

    /**
     * 取消方：1 乘客、3 系统等，与 trip_order.cancel_by 一致；未取消为 null。
     */
    private Integer cancelBy;
    /** 取消原因（系统取消待派单超时时为固定文案） */
    private String cancelReason;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getProvinceCode() {
        return provinceCode;
    }

    public void setProvinceCode(String provinceCode) {
        this.provinceCode = provinceCode;
    }

    public String getCityCode() {
        return cityCode;
    }

    public void setCityCode(String cityCode) {
        this.cityCode = cityCode;
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

    public Boolean getReDispatching() {
        return reDispatching;
    }

    public void setReDispatching(Boolean reDispatching) {
        this.reDispatching = reDispatching;
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
}
