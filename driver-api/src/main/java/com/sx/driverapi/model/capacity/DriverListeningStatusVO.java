package com.sx.driverapi.model.capacity;

/**
 * 司机端听单状态摘要，供 H5 控制上线/下线按钮可用性。
 */
public class DriverListeningStatusVO {

    private Integer monitorStatus;

    public Integer getMonitorStatus() {
        return monitorStatus;
    }

    public void setMonitorStatus(Integer monitorStatus) {
        this.monitorStatus = monitorStatus;
    }
}
