package com.sx.passengerapi.model.wallet;

import java.math.BigDecimal;

public class CouponLockResult {
    private Long couponId;
    private Long templateId;
    private Long companyId;
    private String companyNo;
    private String companyNameSnapshot;
    private String teamIdSnapshot;
    private String teamNameSnapshot;
    private String couponType;
    private String couponRuleSnapshot;
    private BigDecimal discountAmount;
    private BigDecimal payableAmount;

    public Long getCouponId() {
        return couponId;
    }

    public void setCouponId(Long couponId) {
        this.couponId = couponId;
    }

    public Long getTemplateId() {
        return templateId;
    }

    public void setTemplateId(Long templateId) {
        this.templateId = templateId;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }

    public String getCompanyNo() {
        return companyNo;
    }

    public void setCompanyNo(String companyNo) {
        this.companyNo = companyNo;
    }

    public String getCompanyNameSnapshot() {
        return companyNameSnapshot;
    }

    public void setCompanyNameSnapshot(String companyNameSnapshot) {
        this.companyNameSnapshot = companyNameSnapshot;
    }

    public String getTeamIdSnapshot() {
        return teamIdSnapshot;
    }

    public void setTeamIdSnapshot(String teamIdSnapshot) {
        this.teamIdSnapshot = teamIdSnapshot;
    }

    public String getTeamNameSnapshot() {
        return teamNameSnapshot;
    }

    public void setTeamNameSnapshot(String teamNameSnapshot) {
        this.teamNameSnapshot = teamNameSnapshot;
    }

    public String getCouponType() {
        return couponType;
    }

    public void setCouponType(String couponType) {
        this.couponType = couponType;
    }

    public String getCouponRuleSnapshot() {
        return couponRuleSnapshot;
    }

    public void setCouponRuleSnapshot(String couponRuleSnapshot) {
        this.couponRuleSnapshot = couponRuleSnapshot;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public BigDecimal getPayableAmount() {
        return payableAmount;
    }

    public void setPayableAmount(BigDecimal payableAmount) {
        this.payableAmount = payableAmount;
    }
}
