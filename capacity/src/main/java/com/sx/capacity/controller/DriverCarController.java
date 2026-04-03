package com.sx.capacity.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.capacity.common.util.ResultUtil;
import com.sx.capacity.common.vo.PageVo;
import com.sx.capacity.common.vo.ResponseVo;
import com.sx.capacity.dao.CarEntityMapper;
import com.sx.capacity.dao.DriverEntityMapper;
import com.sx.capacity.model.Car;
import com.sx.capacity.model.Driver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 指定司机名下车辆分页列表（与 {@link DriverController} 同前缀 {@code /api/v1/drivers}，路径不冲突）。
 */
@RestController
@RequestMapping("/api/v1/drivers")
public class DriverCarController {

    private final DriverEntityMapper driverEntityMapper;
    private final CarEntityMapper carEntityMapper;

    public DriverCarController(DriverEntityMapper driverEntityMapper, CarEntityMapper carEntityMapper) {
        this.driverEntityMapper = driverEntityMapper;
        this.carEntityMapper = carEntityMapper;
    }

    /**
     * 某司机绑定车辆分页。
     * <p>{@code GET /api/v1/drivers/{driverId}/cars?pageNo=&pageSize=}</p>
     * <p>司机不存在或已删除时返回空列表。</p>
     */
    @GetMapping("/{driverId}/cars")
    public ResponseVo<PageVo<Car>> pageByDriver(@PathVariable Long driverId,
                                                @RequestParam(defaultValue = "1") Integer pageNo,
                                                @RequestParam(defaultValue = "10") Integer pageSize) {
        Driver driver = driverEntityMapper.selectById(driverId);
        if (driver == null || driver.getIsDeleted() == null || driver.getIsDeleted() != 0) {
            PageVo<Car> empty = new PageVo<>();
            empty.setList(List.of());
            empty.setTotal(0L);
            empty.setPageNo(pageNo);
            empty.setPageSize(pageSize);
            return ResultUtil.success(empty);
        }

        int safePageNo = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int safePageSize = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 200);
        long offset = (long) (safePageNo - 1) * safePageSize;

        var qw = Wrappers.<Car>lambdaQuery()
                .eq(Car::getIsDeleted, 0)
                .eq(Car::getDriverId, driverId)
                .orderByDesc(Car::getId);
        Long total = carEntityMapper.selectCount(qw);
        var rows = carEntityMapper.selectList(qw.last("LIMIT " + offset + "," + safePageSize));

        PageVo<Car> resp = new PageVo<>();
        resp.setList(rows);
        resp.setTotal(total == null ? 0L : total);
        resp.setPageNo(safePageNo);
        resp.setPageSize(safePageSize);
        return ResultUtil.success(resp);
    }
}

