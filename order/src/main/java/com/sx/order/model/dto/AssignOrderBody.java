package com.sx.order.model.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 指派司机入参（order-service 内部接口）。
 */
public class AssignOrderBody {

    @NotNull(message = "driverId不能为空")
    private Long driverId;

    private Long carId;
    private Long companyId;

    /** 司机到达上车点 ETA（秒，可选） */
    private Long etaSeconds;

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

    public Long getEtaSeconds() {
        return etaSeconds;
    }

    public void setEtaSeconds(Long etaSeconds) {
        this.etaSeconds = etaSeconds;
    }
}

