package com.sx.capacity.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.capacity.common.util.ResultUtil;
import com.sx.capacity.common.vo.PageVo;
import com.sx.capacity.common.vo.ResponseVo;
import com.sx.capacity.dao.CarEntityMapper;
import com.sx.capacity.model.Car;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运力车辆分页查询（核心服务直连，管理端/聚合层可调用）。
 * 统一前缀：{@code /api/v1/cars}。
 */
@RestController
@RequestMapping("/api/v1/cars")
public class CarController {

    private final CarEntityMapper carEntityMapper;

    public CarController(CarEntityMapper carEntityMapper) {
        this.carEntityMapper = carEntityMapper;
    }

    /**
     * 车辆分页列表。
     * {@code GET /api/v1/cars?pageNo=&pageSize=&driverId=&carNo=}
     */
    @GetMapping
    public ResponseVo<PageVo<Car>> page(@RequestParam(defaultValue = "1") Integer pageNo,
                                        @RequestParam(defaultValue = "10") Integer pageSize,
                                        @RequestParam(required = false) Long driverId,
                                        @RequestParam(required = false) String carNo) {
        int safePageNo = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int safePageSize = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 200);
        long offset = (long) (safePageNo - 1) * safePageSize;

        var qw = Wrappers.<Car>lambdaQuery()
                .eq(Car::getIsDeleted, 0)
                .eq(driverId != null, Car::getDriverId, driverId)
                .like(carNo != null && !carNo.isBlank(), Car::getCarNo, carNo)
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

