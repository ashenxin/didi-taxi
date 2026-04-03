package com.sx.passengerapi.model.ordercore;

import java.math.BigDecimal;

/**
 * order-service 创建订单入参（Feign）。
 */
public class CreateOrderBody {
    private Long passengerId;
    private String provinceCode;
    private String cityCode;
    private String productCode;
    private Place origin;
    private Place dest;
    private BigDecimal estimatedAmount;
    private Long fareRuleId;
    private String fareRuleSnapshot;

    public Long getPassengerId() {
        return passengerId;
    }

    public void setPassengerId(Long passengerId) {
        this.passengerId = passengerId;
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

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public Place getOrigin() {
        return origin;
    }

    public void setOrigin(Place origin) {
        this.origin = origin;
    }

    public Place getDest() {
        return dest;
    }

    public void setDest(Place dest) {
        this.dest = dest;
    }

    public BigDecimal getEstimatedAmount() {
        return estimatedAmount;
    }

    public void setEstimatedAmount(BigDecimal estimatedAmount) {
        this.estimatedAmount = estimatedAmount;
    }

    public Long getFareRuleId() {
        return fareRuleId;
    }

    public void setFareRuleId(Long fareRuleId) {
        this.fareRuleId = fareRuleId;
    }

    public String getFareRuleSnapshot() {
        return fareRuleSnapshot;
    }

    public void setFareRuleSnapshot(String fareRuleSnapshot) {
        this.fareRuleSnapshot = fareRuleSnapshot;
    }
}

