package com.sx.passengerapi.model.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class CreateAndAssignOrderBody {

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
}

