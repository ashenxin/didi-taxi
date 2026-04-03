package com.sx.capacity.model.dto;

/**
 * 最小派单候选（最近司机）返回数据。
 *
 * <p>MVP：先返回一个“可接单且在线”的司机 + 其绑定车辆信息，后续再接入地图矩阵计算“最近”。</p>
 */
public class NearestDriverResult {
    private Long driverId;
    private Long companyId;
    private Long carId;
    private String carNo;
    private String cityCode;
    private String productCode;

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }

    public Long getCarId() {
        return carId;
    }

    public void setCarId(Long carId) {
        this.carId = carId;
    }

    public String getCarNo() {
        return carNo;
    }

    public void setCarNo(String carNo) {
        this.carNo = carNo;
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
}

