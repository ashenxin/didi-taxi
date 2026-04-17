package com.sx.capacity.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.capacity.dao.CarEntityMapper;
import com.sx.capacity.dao.DriverEntityMapper;
import com.sx.capacity.model.Car;
import com.sx.capacity.model.Driver;
import com.sx.capacity.model.dto.NearestDriverResult;
import com.sx.capacity.service.geo.DriverGeoRedisPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 派单候选：优先 Redis GEO（上车点 + 半径）；无坐标或池为空时回退为 DB 首条（与旧 MVP 一致）。
 */
@Service
@Slf4j
public class NearestDriverQueryService {

    private final DriverEntityMapper driverEntityMapper;
    private final CarEntityMapper carEntityMapper;
    private final DriverGeoRedisPool driverGeoRedisPool;
    private final double matchRadiusMeters;

    public NearestDriverQueryService(DriverEntityMapper driverEntityMapper,
                                     CarEntityMapper carEntityMapper,
                                     DriverGeoRedisPool driverGeoRedisPool,
                                     @Value("${capacity.dispatch.match-radius-meters:3000}") double matchRadiusMeters) {
        this.driverEntityMapper = driverEntityMapper;
        this.carEntityMapper = carEntityMapper;
        this.driverGeoRedisPool = driverGeoRedisPool;
        this.matchRadiusMeters = matchRadiusMeters;
    }

    public NearestDriverResult findNearest(String cityCode, String productCode, Double originLat, Double originLng) {
        if (cityCode == null || cityCode.isBlank()) {
            return null;
        }
        if (originLat != null && originLng != null) {
            List<Long> ids = driverGeoRedisPool.listNearestDriverIds(cityCode, originLat, originLng, matchRadiusMeters, 32);
            for (Long driverId : ids) {
                NearestDriverResult r = buildEligible(driverId, cityCode, productCode);
                if (r != null) {
                    log.info("最近司机：Redis GEO 命中 driverId={} cityCode={}", driverId, cityCode);
                    return r;
                }
            }
        }
        return findNearestDbFallback(cityCode, productCode);
    }

    /**
     * 将指定司机在 DB 中构造成派单结果（车型/在线/听单等校验与 {@link #findNearestDbFallback} 一致）。
     */
    public NearestDriverResult buildEligibleForDriver(Long driverId, String cityCode, String productCode) {
        return buildEligible(driverId, cityCode, productCode);
    }

    private NearestDriverResult buildEligible(Long driverId, String cityCode, String productCode) {
        var carQw = Wrappers.<Car>lambdaQuery()
                .eq(Car::getIsDeleted, 0)
                .eq(Car::getCarState, 0)
                .eq(Car::getCityCode, cityCode)
                .eq(Car::getDriverId, driverId);
        if (productCode != null && !productCode.isBlank()) {
            carQw.eq(Car::getRideTypeId, productCode);
        }
        Car car = carEntityMapper.selectOne(carQw.last("LIMIT 1"));
        if (car == null) {
            return null;
        }
        Driver d = driverEntityMapper.selectById(driverId);
        if (d == null) {
            return null;
        }
        if (!Objects.equals(d.getIsDeleted(), 0)) {
            return null;
        }
        if (!Objects.equals(d.getCanAcceptOrder(), 1)) {
            return null;
        }
        Integer ms = d.getMonitorStatus();
        if (!(Objects.equals(ms, 1) || Objects.equals(ms, 2))) {
            return null;
        }
        if (!Objects.equals(cityCode, d.getCityCode())) {
            return null;
        }
        NearestDriverResult resp = new NearestDriverResult();
        resp.setDriverId(d.getId());
        resp.setCompanyId(d.getCompanyId());
        resp.setCarId(car.getId());
        resp.setCarNo(car.getCarNo());
        resp.setCityCode(cityCode);
        resp.setProductCode(productCode);
        return resp;
    }

    private NearestDriverResult findNearestDbFallback(String cityCode, String productCode) {
        var carQw = Wrappers.<Car>lambdaQuery()
                .eq(Car::getIsDeleted, 0)
                .eq(Car::getCarState, 0)
                .eq(Car::getCityCode, cityCode)
                .isNotNull(Car::getDriverId);
        if (productCode != null && !productCode.isBlank()) {
            carQw.eq(Car::getRideTypeId, productCode);
        }
        List<Car> cars = carEntityMapper.selectList(carQw.last("LIMIT 200"));
        if (cars == null || cars.isEmpty()) {
            log.warn("最近司机：无可用车辆 cityCode={} productCode={}", cityCode, productCode);
            return null;
        }

        List<Long> driverIds = cars.stream().map(Car::getDriverId).filter(Objects::nonNull).distinct().toList();
        List<Driver> drivers = driverEntityMapper.selectBatchIds(driverIds);
        Map<Long, Driver> driverMap = drivers == null ? new HashMap<>() :
                drivers.stream().collect(Collectors.toMap(Driver::getId, d -> d, (a, b) -> a));

        for (Car car : cars) {
            Driver d = driverMap.get(car.getDriverId());
            if (d == null) {
                continue;
            }
            if (!Objects.equals(d.getIsDeleted(), 0)) {
                continue;
            }
            if (!Objects.equals(d.getCanAcceptOrder(), 1)) {
                continue;
            }
            Integer ms = d.getMonitorStatus();
            if (!(Objects.equals(ms, 1) || Objects.equals(ms, 2))) {
                continue;
            }
            if (!Objects.equals(cityCode, d.getCityCode())) {
                continue;
            }

            NearestDriverResult resp = new NearestDriverResult();
            resp.setDriverId(d.getId());
            resp.setCompanyId(d.getCompanyId());
            resp.setCarId(car.getId());
            resp.setCarNo(car.getCarNo());
            resp.setCityCode(cityCode);
            resp.setProductCode(productCode);
            log.info("最近司机：数据库回退命中 driverId={} carId={} cityCode={}", d.getId(), car.getId(), cityCode);
            return resp;
        }

        log.warn("最近司机：无符合条件的司机 cityCode={} productCode={}", cityCode, productCode);
        return null;
    }
}
