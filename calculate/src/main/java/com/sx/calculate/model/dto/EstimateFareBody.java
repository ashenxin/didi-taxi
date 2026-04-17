package com.sx.calculate.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class EstimateFareBody {

    @NotNull(message = "companyId不能为空")
    private Long companyId;

    @NotBlank(message = "provinceCode不能为空")
    private String provinceCode;

    @NotBlank(message = "cityCode不能为空")
    private String cityCode;

    @NotBlank(message = "productCode不能为空")
    private String productCode;

    @NotNull(message = "distanceMeters不能为空")
    private Long distanceMeters;

    @NotNull(message = "durationSeconds不能为空")
    private Long durationSeconds;

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

    public Long getDistanceMeters() {
        return distanceMeters;
    }

    public void setDistanceMeters(Long distanceMeters) {
        this.distanceMeters = distanceMeters;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Long durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }
}

