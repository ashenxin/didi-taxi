package com.sx.capacity.model.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 指派成功后写入订单池索引（派生数据，以订单库为准）。
 */
public class PendingOrderIndexBody {

    @NotNull(message = "driverId不能为空")
    private Long driverId;

    @NotBlank(message = "orderNo不能为空")
    private String orderNo;

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }
}
