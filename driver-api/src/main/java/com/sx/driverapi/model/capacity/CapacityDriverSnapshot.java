package com.sx.driverapi.model.capacity;

/**
 * 与运力 {@code GET /api/v1/drivers/{id}} 返回 JSON 对齐；Feign 仅反序列化所需字段。
 * {@code monitor_status}：0 未听单，1 听单中，2 服务中等（与运力库表一致）。
 */
public class CapacityDriverSnapshot {

    private Integer monitorStatus;

    public Integer getMonitorStatus() {
        return monitorStatus;
    }

    public void setMonitorStatus(Integer monitorStatus) {
        this.monitorStatus = monitorStatus;
    }
}
