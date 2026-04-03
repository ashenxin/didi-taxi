package com.sx.driverapi.model.order;

import java.io.Serializable;

public class AssignedOrderItemVO implements Serializable {
    private String orderNo;
    /** 文档示例为 ASSIGNED 等枚举名 */
    private String status;
    private Pickup pickup;
    /** 预计到达上车点秒数；MVP 暂无精确 ETA 时可为 null */
    private Integer etaSeconds;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Pickup getPickup() {
        return pickup;
    }

    public void setPickup(Pickup pickup) {
        this.pickup = pickup;
    }

    public Integer getEtaSeconds() {
        return etaSeconds;
    }

    public void setEtaSeconds(Integer etaSeconds) {
        this.etaSeconds = etaSeconds;
    }

    public static class Pickup implements Serializable {
        private String name;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
