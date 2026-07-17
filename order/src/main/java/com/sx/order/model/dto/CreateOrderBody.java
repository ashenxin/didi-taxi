package com.sx.order.model.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * 创建订单入参（order-service 内部接口）。
 */
public class CreateOrderBody {

    @NotNull(message = "passengerId不能为空")
    private Long passengerId;

    @NotBlank(message = "provinceCode不能为空")
    private String provinceCode;

    @NotBlank(message = "cityCode不能为空")
    private String cityCode;

    @NotBlank(message = "productCode不能为空")
    private String productCode;

    @NotNull(message = "origin不能为空")
    @Valid
    private Place origin;

    @NotNull(message = "dest不能为空")
    @Valid
    private Place dest;

    /** 预估费用（元，可空；后续由 calculate.estimate 填入） */
    private BigDecimal estimatedAmount;

    /** 命中的计价规则 ID（可空） */
    private Long fareRuleId;

    /** 计价规则快照（JSON，可空） */
    private String fareRuleSnapshot;

    @NotNull(message = "plannedDistanceMeters不能为空")
    private Long plannedDistanceMeters;

    @NotNull(message = "plannedDurationSeconds不能为空")
    private Long plannedDurationSeconds;

    @NotBlank(message = "distanceSource不能为空")
    private String distanceSource;

    @NotBlank(message = "fareCalculationVersion不能为空")
    private String fareCalculationVersion;

    @NotBlank(message = "routeMockVersion不能为空")
    private String routeMockVersion;

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

    public Long getPlannedDistanceMeters() {
        return plannedDistanceMeters;
    }

    public void setPlannedDistanceMeters(Long plannedDistanceMeters) {
        this.plannedDistanceMeters = plannedDistanceMeters;
    }

    public Long getPlannedDurationSeconds() {
        return plannedDurationSeconds;
    }

    public void setPlannedDurationSeconds(Long plannedDurationSeconds) {
        this.plannedDurationSeconds = plannedDurationSeconds;
    }

    public String getDistanceSource() {
        return distanceSource;
    }

    public void setDistanceSource(String distanceSource) {
        this.distanceSource = distanceSource;
    }

    public String getFareCalculationVersion() {
        return fareCalculationVersion;
    }

    public void setFareCalculationVersion(String fareCalculationVersion) {
        this.fareCalculationVersion = fareCalculationVersion;
    }

    public String getRouteMockVersion() {
        return routeMockVersion;
    }

    public void setRouteMockVersion(String routeMockVersion) {
        this.routeMockVersion = routeMockVersion;
    }
}
