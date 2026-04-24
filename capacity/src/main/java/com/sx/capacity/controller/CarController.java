package com.sx.capacity.controller;

import com.sx.capacity.common.util.ResultUtil;
import com.sx.capacity.common.vo.PageVo;
import com.sx.capacity.common.vo.ResponseVo;
import com.sx.capacity.dao.CarEntityMapper;
import com.sx.capacity.model.dto.CarPageRow;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
     * {@code GET /api/v1/cars?pageNo=&pageSize=&provinceCode=&cityCode=&companyId=&driverId=&driverPhone=&carNo=&brandName=&rideTypeId=}
     */
    @GetMapping
    public ResponseVo<PageVo<CarPageRow>> page(@RequestParam(defaultValue = "1") Integer pageNo,
                                              @RequestParam(defaultValue = "10") Integer pageSize,
                                              @RequestParam(required = false) String provinceCode,
                                              @RequestParam(required = false) String cityCode,
                                              @RequestParam(required = false) Long companyId,
                                              @RequestParam(required = false) Long driverId,
                                              @RequestParam(required = false) String driverPhone,
                                              @RequestParam(required = false) String carNo,
                                              @RequestParam(required = false) String brandName,
                                              @RequestParam(required = false) String rideTypeId) {
        int safePageNo = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int safePageSize = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 200);
        long offset = (long) (safePageNo - 1) * safePageSize;

        String p = provinceCode != null ? provinceCode.trim() : null;
        String c = cityCode != null ? cityCode.trim() : null;
        String dp = driverPhone != null ? driverPhone.trim() : null;
        String cn = carNo != null ? carNo.trim() : null;
        String bn = brandName != null ? brandName.trim() : null;
        String rt = rideTypeId != null ? rideTypeId.trim() : null;

        Long total = carEntityMapper.countCarPage(p, c, companyId, driverId, dp, cn, bn, rt);
        var rows = carEntityMapper.selectCarPage(p, c, companyId, driverId, dp, cn, bn, rt, offset, safePageSize);

        PageVo<CarPageRow> resp = new PageVo<>();
        resp.setList(rows);
        resp.setTotal(total == null ? 0L : total);
        resp.setPageNo(safePageNo);
        resp.setPageSize(safePageSize);
        return ResultUtil.success(resp);
    }

    /**
     * 车辆详情（包含司机与公司信息）。
     * {@code GET /api/v1/cars/{id}}
     */
    @GetMapping("/{id}")
    public ResponseVo<CarPageRow> detail(@PathVariable Long id) {
        CarPageRow row = carEntityMapper.selectCarDetail(id);
        return ResultUtil.success(row);
    }
}

