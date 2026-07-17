package com.sx.passengerapi.model.settlement;

import java.math.BigDecimal;

public class OrderSettlementRow {
    private String orderNo;
    private Long passengerId;
    private BigDecimal finalAmount;
    private BigDecimal couponDiscountAmount;
    private BigDecimal payableAmount;
    private BigDecimal paidAmount;
    private String couponCompanyNameSnapshot;
    private String couponType;
    private String settlementStatus;
    private Integer manualActionRequired;

    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getPassengerId() { return passengerId; }
    public void setPassengerId(Long passengerId) { this.passengerId = passengerId; }
    public BigDecimal getFinalAmount() { return finalAmount; }
    public void setFinalAmount(BigDecimal finalAmount) { this.finalAmount = finalAmount; }
    public BigDecimal getCouponDiscountAmount() { return couponDiscountAmount; }
    public void setCouponDiscountAmount(BigDecimal couponDiscountAmount) { this.couponDiscountAmount = couponDiscountAmount; }
    public BigDecimal getPayableAmount() { return payableAmount; }
    public void setPayableAmount(BigDecimal payableAmount) { this.payableAmount = payableAmount; }
    public BigDecimal getPaidAmount() { return paidAmount; }
    public void setPaidAmount(BigDecimal paidAmount) { this.paidAmount = paidAmount; }
    public String getCouponCompanyNameSnapshot() { return couponCompanyNameSnapshot; }
    public void setCouponCompanyNameSnapshot(String value) { this.couponCompanyNameSnapshot = value; }
    public String getCouponType() { return couponType; }
    public void setCouponType(String couponType) { this.couponType = couponType; }
    public String getSettlementStatus() { return settlementStatus; }
    public void setSettlementStatus(String settlementStatus) { this.settlementStatus = settlementStatus; }
    public Integer getManualActionRequired() { return manualActionRequired; }
    public void setManualActionRequired(Integer manualActionRequired) { this.manualActionRequired = manualActionRequired; }
}
