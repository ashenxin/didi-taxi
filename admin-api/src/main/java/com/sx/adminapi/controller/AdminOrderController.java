package com.sx.adminapi.controller;

import com.sx.adminapi.common.util.ResultUtil;
import com.sx.adminapi.common.vo.ResponseVo;
import com.sx.adminapi.model.order.AdminOrderDetailVO;
import com.sx.adminapi.model.order.AdminOrderPageVO;
import com.sx.adminapi.service.AdminOrderService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 管理后台订单 BFF：列表与详情（含事件时间线），聚合 order-service 等。
 * 统一前缀：{@code /admin/api/v1/orders}。
 * 列表 {@code provinceCode}/{@code cityCode} 与登录数据域合并；详情按订单归属省市校验，跨域返回「订单不存在」。
 */
@RestController
@RequestMapping("/admin/api/v1/orders")
public class AdminOrderController {

    private final AdminOrderService adminOrderService;

    public AdminOrderController(AdminOrderService adminOrderService) {
        this.adminOrderService = adminOrderService;
    }

    /**
     * 订单分页列表（支持按乘客手机号反查）；地区筛选受 {@link com.sx.adminapi.security.AdminDataScope} 约束。
     * {@code GET /admin/api/v1/orders?orderNo=&phone=&provinceCode=&cityCode=&status=&createdAtStart=&createdAtEnd=&pageNo=&pageSize=}
     */
    @GetMapping
    public ResponseVo<AdminOrderPageVO> page(@RequestParam(required = false) String orderNo,
                                             @RequestParam(required = false) String phone,
                                             @RequestParam(required = false) String provinceCode,
                                             @RequestParam(required = false) String cityCode,
                                             @RequestParam(required = false) Integer status,
                                             @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAtStart,
                                             @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAtEnd,
                                             @RequestParam(required = false, defaultValue = "1") Integer pageNo,
                                             @RequestParam(required = false, defaultValue = "10") Integer pageSize) {
        return ResultUtil.success(adminOrderService.page(
                orderNo, phone, provinceCode, cityCode, status, createdAtStart, createdAtEnd, pageNo, pageSize));
    }

    /**
     * 订单详情（含事件时间线）；订单不在当前用户数据域内时 404。
     * {@code GET /admin/api/v1/orders/{orderNo}}
     */
    @GetMapping("/{orderNo}")
    public ResponseVo<AdminOrderDetailVO> detail(@PathVariable String orderNo) {
        return ResultUtil.success(adminOrderService.detail(orderNo));
    }
}
