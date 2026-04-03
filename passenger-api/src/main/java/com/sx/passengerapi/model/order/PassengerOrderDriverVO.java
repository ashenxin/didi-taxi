package com.sx.passengerapi.model.order;

/**
 * 乘客端展示的司机侧摘要（后续可经 capacity 补全姓名、车牌等）。
 */
public class PassengerOrderDriverVO {
    private Long driverId;
    private Long carId;
    private Long companyId;

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public Long getCarId() {
        return carId;
    }

    public void setCarId(Long carId) {
        this.carId = carId;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }
}
