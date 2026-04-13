package com.sx.capacity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.capacity.dao.DriverEntityMapper;
import com.sx.capacity.model.Driver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Objects;

@Service
@Slf4j
public class DriverStatusService {

    private final DriverEntityMapper driverEntityMapper;

    public DriverStatusService(DriverEntityMapper driverEntityMapper) {
        this.driverEntityMapper = driverEntityMapper;
    }

    /**
     * 司机上线/下线：更新 {@code monitor_status}（0 未听单，1 听单中；服务中 2 由业务后续写入）。
     */
    @Transactional
    public void setOnline(Long driverId, boolean online) {
        if (driverId == null) {
            throw new IllegalArgumentException("driverId不能为空");
        }
        Driver d = driverEntityMapper.selectOne(Wrappers.<Driver>lambdaQuery()
                .eq(Driver::getId, driverId)
                .eq(Driver::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (d == null) {
            throw new IllegalArgumentException("司机不存在");
        }
        if (online && !Objects.equals(d.getCanAcceptOrder(), 1)) {
            throw new IllegalArgumentException("当前不可接单，无法上线听单");
        }
        int monitor = online ? 1 : 0;
        driverEntityMapper.update(null, Wrappers.<Driver>lambdaUpdate()
                .set(Driver::getMonitorStatus, monitor)
                .set(Driver::getUpdatedAt, new Date())
                .eq(Driver::getId, driverId)
                .eq(Driver::getIsDeleted, 0));
        log.info("driver monitor updated driverId={} online={} monitorStatus={}", driverId, online, monitor);
    }

    /**
     * 接单前校验：司机存在、可接单、且已上线听单（{@code monitor_status=1}）。
     */
    public void assertReadyToAccept(Long driverId) {
        if (driverId == null) {
            throw new IllegalArgumentException("driverId不能为空");
        }
        Driver d = driverEntityMapper.selectOne(Wrappers.<Driver>lambdaQuery()
                .eq(Driver::getId, driverId)
                .eq(Driver::getIsDeleted, 0)
                .last("LIMIT 1"));
        if (d == null) {
            throw new IllegalArgumentException("司机不存在");
        }
        if (!Objects.equals(d.getCanAcceptOrder(), 1)) {
            throw new IllegalArgumentException("当前不可接单");
        }
        if (!Objects.equals(d.getMonitorStatus(), 1)) {
            throw new IllegalArgumentException("请先上线听单");
        }
    }
}
