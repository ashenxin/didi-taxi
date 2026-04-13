package com.sx.capacity.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.capacity.common.util.ResultUtil;
import com.sx.capacity.common.vo.ResponseVo;
import com.sx.capacity.dao.CarEntityMapper;
import com.sx.capacity.dao.DriverEntityMapper;
import com.sx.capacity.model.Car;
import com.sx.capacity.model.Driver;
import com.sx.capacity.model.dto.NearestDriverResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 派单相关接口（MVP：按城市/车型筛选可接单在线司机，返回首个候选）。
 * 统一前缀：{@code /api/v1/dispatch}；通常由 {@code passenger-api} 下单链路调用。
 */
@RestController
@RequestMapping("/api/v1/dispatch")
@Slf4j
public class DispatchController {

    private final DriverEntityMapper driverEntityMapper;
    private final CarEntityMapper carEntityMapper;

    public DispatchController(DriverEntityMapper driverEntityMapper, CarEntityMapper carEntityMapper) {
        this.driverEntityMapper = driverEntityMapper;
        this.carEntityMapper = carEntityMapper;
    }

    /**
     * 查询「可派单」司机（MVP：非真实距离最近，取满足条件的首条）。
     * {@code GET /api/v1/dispatch/nearest-driver?cityCode=&productCode=}
     * 筛选：车辆在城市内、司机在线(1/2)、{@code can_accept_order=1}；{@code productCode} 非空时匹配车辆 {@code ride_type_id}。
     * 无可用候选时业务码 {@code 404}。
     */
    @GetMapping("/nearest-driver")
    public ResponseVo<NearestDriverResult> searchNearestDriver(@RequestParam String cityCode,
                                                              @RequestParam(required = false) String productCode) {
        if (cityCode == null || cityCode.isBlank()) {
            return ResultUtil.requestError("cityCode不能为空");
        }

        // 先从车辆表取候选（司机 1-1 车辆），可减少后续组合复杂度
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
            log.warn("nearest-driver: no cars cityCode={} productCode={}", cityCode, productCode);
            return ResultUtil.error(404, "无可用车辆/司机");
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
            // 在线：monitor_status=1/2；可接单：can_accept_order=1；城市：cityCode
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
            log.info("nearest-driver: hit driverId={} carId={} cityCode={}", d.getId(), car.getId(), cityCode);
            return ResultUtil.success(resp);
        }

        log.warn("nearest-driver: no eligible driver cityCode={} productCode={}", cityCode, productCode);
        return ResultUtil.error(404, "无可接单在线司机");
    }
}

