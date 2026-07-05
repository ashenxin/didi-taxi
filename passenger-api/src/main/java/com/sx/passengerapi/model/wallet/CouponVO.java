package com.sx.passengerapi.model.wallet;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class CouponVO {
    private Long couponId;
    private String couponName;
    private BigDecimal thresholdAmount;
    private BigDecimal discountAmount;
    private String cityCode;
    private String productCode;
    private String status;
    private LocalDateTime validStartAt;
    private LocalDateTime validEndAt;

    public Long getCouponId() {
        return couponId;
    }

    public void setCouponId(Long couponId) {
        this.couponId = couponId;
    }

    public String getCouponName() {
        return couponName;
    }

    public void setCouponName(String couponName) {
        this.couponName = couponName;
    }

    public BigDecimal getThresholdAmount() {
        return thresholdAmount;
    }

    public void setThresholdAmount(BigDecimal thresholdAmount) {
        this.thresholdAmount = thresholdAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getValidStartAt() {
        return validStartAt;
    }

    public void setValidStartAt(LocalDateTime validStartAt) {
        this.validStartAt = validStartAt;
    }

    public LocalDateTime getValidEndAt() {
        return validEndAt;
    }

    public void setValidEndAt(LocalDateTime validEndAt) {
        this.validEndAt = validEndAt;
    }
}
