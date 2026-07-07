package com.sx.passengerapi.model.wallet;

import java.math.BigDecimal;

public class CouponLockRequest {
    private Long passengerId;
    private String orderNo;
    private Long couponId;
    private BigDecimal finalAmount;
    private Long companyId;
    private String cityCode;
    private String productCode;
    private Boolean manualNoCoupon;

    public Long getPassengerId() {
        return passengerId;
    }

    public void setPassengerId(Long passengerId) {
        this.passengerId = passengerId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getCouponId() {
        return couponId;
    }

    public void setCouponId(Long couponId) {
        this.couponId = couponId;
    }

    public BigDecimal getFinalAmount() {
        return finalAmount;
    }

    public void setFinalAmount(BigDecimal finalAmount) {
        this.finalAmount = finalAmount;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }

    public String getCityCode() {
        return cityCode;
    }

    public void setCityCode(String cityCode) {
        this.cityCode = cityCode;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public Boolean getManualNoCoupon() {
        return manualNoCoupon;
    }

    public void setManualNoCoupon(Boolean manualNoCoupon) {
        this.manualNoCoupon = manualNoCoupon;
    }
}
