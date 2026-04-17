package com.sx.capacity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.capacity.config.CapacityDispatchProperties;
import com.sx.capacity.dao.DriverEntityMapper;
import com.sx.capacity.model.Driver;
import com.sx.capacity.service.geo.DriverGeoRedisPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Date;
import java.util.Objects;

@Service
@Slf4j
public class DriverStatusService {

    private final DriverEntityMapper driverEntityMapper;
    private final DriverGeoRedisPool driverGeoRedisPool;
    private final LateDispatchMatchService lateDispatchMatchService;
    private final CapacityDispatchProperties dispatchProperties;

    public DriverStatusService(DriverEntityMapper driverEntityMapper,
                               DriverGeoRedisPool driverGeoRedisPool,
                               LateDispatchMatchService lateDispatchMatchService,
                               CapacityDispatchProperties dispatchProperties) {
        this.driverEntityMapper = driverEntityMapper;
        this.driverGeoRedisPool = driverGeoRedisPool;
        this.lateDispatchMatchService = lateDispatchMatchService;
        this.dispatchProperties = dispatchProperties;
    }

    /**
     * 司机上线/下线：更新 {@code monitor_status}（0 未听单，1 听单中；服务中 2 由业务后续写入）。
     * 若上线且携带 {@code lat/lng}，写入 Redis 司机池并尝试迟滞匹配待派单订单。
     */
    @Transactional
    public void setOnline(Long driverId, boolean online, Double lat, Double lng) {
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
        log.info("司机听单状态已更新 driverId={} online={} monitorStatus={}", driverId, online, monitor);

        final String cityCode = d.getCityCode();
        final Long did = driverId;
        final boolean on = online;
        double[] geoUse = resolveOnlineGeoCoords(driverId, lat, lng);
        final Double flat = geoUse == null ? null : geoUse[0];
        final Double flng = geoUse == null ? null : geoUse[1];
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    if (on && flat != null && flng != null) {
                        driverGeoRedisPool.add(cityCode, did, flat, flng);
                        lateDispatchMatchService.tryMatchAfterDriverOnline(did, cityCode, flat, flng);
                    } else if (!on) {
                        if (cityCode == null || cityCode.isBlank()) {
                            log.warn("司机下线/登出同步：cityCode 为空，无法从 Redis GEO 移除 driverId={}", did);
                        } else {
                            driverGeoRedisPool.remove(cityCode, did);
                        }
                    }
                } catch (Exception e) {
                    log.warn("事务提交后司机入池/匹配失败 driverId={}: {}", did, e.toString());
                }
            }
        });
    }

    /**
     * 自测锚点：{@code capacity.dispatch.geo-pin} 命中时优先使用配置坐标（可与乘客 demo 东站起点一致），否则使用请求体中的 lat/lng。
     */
    private double[] resolveOnlineGeoCoords(Long driverId, Double lat, Double lng) {
        if (driverId == null || dispatchProperties.getGeoPin() == null) {
            return toPairOrNull(lat, lng);
        }
        CapacityDispatchProperties.GeoPin pin = dispatchProperties.getGeoPin().get(driverId);
        if (pin != null) {
            log.info("司机位置锚点已应用 driverId={} lat={} lng={}", driverId, pin.getLat(), pin.getLng());
            return new double[]{pin.getLat(), pin.getLng()};
        }
        return toPairOrNull(lat, lng);
    }

    private static double[] toPairOrNull(Double lat, Double lng) {
        if (lat == null || lng == null) {
            return null;
        }
        return new double[]{lat, lng};
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
