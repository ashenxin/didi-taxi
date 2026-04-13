package com.sx.passengerapi.model.order;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 下单请求体。
 * {@code passengerId} 由网关解析 JWT 后写入 {@code X-User-Id}，Controller 再写入本对象；请求体可不传，勿与 body 内字段混填。
 */
public class CreateAndAssignOrderBody {

    /** 乘客 ID；服务端从 {@code X-User-Id} 注入，客户端无需传。 */
    private Long passengerId;

    @NotBlank(message = "地区信息不完整，请稍后重试")
    private String provinceCode;

    @NotBlank(message = "请选择城市")
    private String cityCode;

    @NotBlank(message = "请选择车型或服务类型")
    private String productCode;

    @NotNull(message = "请填写上车点")
    @Valid
    private Place origin;

    @NotNull(message = "请填写下车点")
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

