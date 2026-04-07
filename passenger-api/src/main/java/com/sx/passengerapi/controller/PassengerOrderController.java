package com.sx.passengerapi.controller;

import com.sx.passengerapi.common.util.ResultUtil;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.order.CancelOrderRequest;
import com.sx.passengerapi.model.order.CreateAndAssignOrderBody;
import com.sx.passengerapi.model.order.CreateAndAssignOrderResult;
import com.sx.passengerapi.model.order.PassengerOrderDetailVO;
import com.sx.passengerapi.service.PassengerOrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 乘客端聚合接口（BFF），供乘客 H5 / App 调用。
 * <p>统一前缀：{@code /app/api/v1}；编排 map 路线预估、calculate 计费、order 建单/指派、capacity 派单等。</p>
 */
@RestController
@RequestMapping("/app/api/v1")
public class PassengerOrderController {

    private final PassengerOrderService passengerOrderService;
    private static final String USER_ID_HEADER = "X-User-Id";

    public PassengerOrderController(PassengerOrderService passengerOrderService) {
        this.passengerOrderService = passengerOrderService;
    }

    /**
     * 下单（同步链路）：路线预估 → 费用预估 → 创建订单 → 最近司机派单（若有）。
     * <p>{@code POST /app/api/v1/orders}</p>
     */
    @PostMapping("/orders")
    public ResponseVo<CreateAndAssignOrderResult> createAndAssign(
            @RequestHeader(value = USER_ID_HEADER, required = false) Long passengerId,
            @RequestBody @Valid CreateAndAssignOrderBody body) {
        if (passengerId == null) {
            throw new com.sx.passengerapi.common.exception.BizErrorException(401, "未授权，请重新登录");
        }
        body.setPassengerId(passengerId);
        return ResultUtil.success(passengerOrderService.createAndAssign(body));
    }

    /**
     * 订单详情（轮询）：校验 {@code passengerId} 与订单乘客一致。
     * <p>{@code GET /app/api/v1/orders/{orderNo}?passengerId=}</p>
     */
    @GetMapping("/orders/{orderNo}")
    public ResponseVo<PassengerOrderDetailVO> orderDetail(
            @PathVariable String orderNo,
            @RequestHeader(value = USER_ID_HEADER, required = false) Long passengerId) {
        if (passengerId == null) {
            throw new com.sx.passengerapi.common.exception.BizErrorException(401, "未授权，请重新登录");
        }
        return ResultUtil.success(passengerOrderService.getOrderDetail(orderNo, passengerId));
    }

    /**
     * 乘客取消订单：透传 order-service。
     * <p>{@code POST /app/api/v1/orders/{orderNo}/cancel}</p>
     */
    @PostMapping("/orders/{orderNo}/cancel")
    public ResponseVo<Void> cancelOrder(@PathVariable String orderNo,
                                        @RequestHeader(value = USER_ID_HEADER, required = false) Long passengerId,
                                        @RequestBody @Valid CancelOrderRequest body) {
        if (passengerId == null) {
            throw new com.sx.passengerapi.common.exception.BizErrorException(401, "未授权，请重新登录");
        }
        body.setPassengerId(passengerId);
        passengerOrderService.cancelOrder(orderNo, body);
        return ResultUtil.success(null);
    }
}

