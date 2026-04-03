package com.sx.capacity.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sx.capacity.dao.DriverEntityMapper;
import com.sx.capacity.common.util.ResultUtil;
import com.sx.capacity.common.vo.PageVo;
import com.sx.capacity.common.vo.ResponseVo;
import com.sx.capacity.model.Driver;
import com.sx.capacity.model.dto.DriverOnlineBody;
import com.sx.capacity.service.DriverStatusService;
import jakarta.validation.Valid;

import java.util.Collections;
import java.util.Objects;

import com.sx.capacity.common.enums.ExceptionCode;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运力侧司机相关接口：听单状态、接单资格校验、管理端分页查询。
 * <p>统一前缀：{@code /api/v1/drivers}；供 {@code driver-api}、管理端、派单模块等调用。</p>
 */
@RestController
@RequestMapping("/api/v1/drivers")
public class DriverController {

    private final DriverEntityMapper driverEntityMapper;
    private final DriverStatusService driverStatusService;

    public DriverController(DriverEntityMapper driverEntityMapper, DriverStatusService driverStatusService) {
        this.driverEntityMapper = driverEntityMapper;
        this.driverStatusService = driverStatusService;
    }

    /**
     * 司机上线/下线（听单开关）。
     * <p>{@code POST /api/v1/drivers/{driverId}/online}，JSON：{@code online: boolean}。</p>
     * <p>上线要求 {@code can_accept_order=1}；更新 {@code monitor_status}（0 未听单 / 1 听单中）。</p>
     */
    @PostMapping("/{driverId}/online")
    public ResponseVo<Void> online(@PathVariable Long driverId, @RequestBody @Valid DriverOnlineBody body) {
        try {
            driverStatusService.setOnline(driverId, Boolean.TRUE.equals(body.getOnline()));
            return ResultUtil.success(null);
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    /**
     * 接单前资格校验：司机存在、{@code can_accept_order=1}、{@code monitor_status=1}（已上线听单）。
     * <p>{@code GET /api/v1/drivers/{driverId}/accept-readiness}</p>
     */
    @GetMapping("/{driverId}/accept-readiness")
    public ResponseVo<Void> acceptReadiness(@PathVariable Long driverId) {
        try {
            driverStatusService.assertReadyToAccept(driverId);
            return ResultUtil.success(null);
        } catch (IllegalArgumentException ex) {
            return ResultUtil.requestError(ex.getMessage());
        }
    }

    /**
     * 司机主键详情（管理端 / BFF 在查车辆列表前拉取 {@code cityCode} 做数据域校验）。
     * <p>{@code GET /api/v1/drivers/{driverId}}</p>
     */
    @GetMapping("/{driverId}")
    public ResponseVo<Driver> getById(@PathVariable Long driverId) {
        Driver d = driverEntityMapper.selectById(driverId);
        if (d == null || Objects.equals(d.getIsDeleted(), 1)) {
            return ResultUtil.error(ExceptionCode.NOT_FOUND.getValue(), "司机不存在");
        }
        return ResultUtil.success(d);
    }

    /**
     * 分页查询司机列表。
     * <p>{@code GET /api/v1/drivers?pageNo=&pageSize=&companyId=&name=&phone=&online=&provinceCode=&cityCode=}</p>
     * <p>{@code cityCode}：精确匹配司机城市；仅传 {@code provinceCode} 且长度≥2 时按「省码前两位」匹配市码前缀（与 admin {@code AdminDataScope#cityBelongsToProvince} 一致）。</p>
     * <p>{@code online}：{@code 1} 听单中或服务中（{@code monitor_status} 为 1/2），{@code 0} 未听单或空。</p>
     */
    @GetMapping
    public ResponseVo<PageVo<Driver>> page(@RequestParam(defaultValue = "1") Integer pageNo,
                                          @RequestParam(defaultValue = "10") Integer pageSize,
                                          @RequestParam(required = false) Long companyId,
                                          @RequestParam(required = false) String name,
                                          @RequestParam(required = false) String phone,
                                          @RequestParam(required = false) Integer online,
                                          @RequestParam(required = false) String provinceCode,
                                          @RequestParam(required = false) String cityCode) {
        int safePageNo = pageNo == null || pageNo < 1 ? 1 : pageNo;
        int safePageSize = pageSize == null || pageSize < 1 ? 10 : Math.min(pageSize, 200);
        long offset = (long) (safePageNo - 1) * safePageSize;

        String pc = StringUtils.hasText(provinceCode) ? provinceCode.trim() : null;
        String cc = StringUtils.hasText(cityCode) ? cityCode.trim() : null;

        var qw = Wrappers.<Driver>lambdaQuery()
                .eq(Driver::getIsDeleted, 0)
                .eq(companyId != null, Driver::getCompanyId, companyId)
                .like(name != null && !name.isBlank(), Driver::getName, name)
                .like(phone != null && !phone.isBlank(), Driver::getPhone, phone)
                .eq(cc != null, Driver::getCityCode, cc);
        // 仅省前缀：与 AdminDataScope.cityBelongsToProvince 一致（省码前两位 = 市码前缀）
        if (pc != null && cc == null && pc.length() >= 2) {
            qw.apply("city_code LIKE CONCAT({0}, '%')", pc.substring(0, 2));
        }
        if (online != null) {
            if (online == 1) {
                qw.in(Driver::getMonitorStatus, 1, 2);
            } else if (online == 0) {
                qw.and(w -> w.eq(Driver::getMonitorStatus, 0).or().isNull(Driver::getMonitorStatus));
            }
        }
        qw.orderByDesc(Driver::getId);
        Long total = driverEntityMapper.selectCount(qw);
        var rows = driverEntityMapper.selectList(qw.last("LIMIT " + offset + "," + safePageSize));

        PageVo<Driver> resp = new PageVo<>();
        resp.setList(rows != null ? rows : Collections.emptyList());
        resp.setTotal(total == null ? 0L : total);
        resp.setPageNo(safePageNo);
        resp.setPageSize(safePageSize);
        return ResultUtil.success(resp);
    }
}
