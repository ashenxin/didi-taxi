package com.sx.passengerapi.controller;

import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.util.ResultUtil;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.order.CancelOrderRequest;
import com.sx.passengerapi.model.order.CreateAndAssignOrderBody;
import com.sx.passengerapi.model.order.CreateAndAssignOrderResult;
import com.sx.passengerapi.model.order.CreateOrderResultV1;
import com.sx.passengerapi.model.order.PassengerOrderDetailVO;
import com.sx.passengerapi.service.PassengerOrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 乘客端聚合接口（BFF），供乘客 H5 / App 调用。
 * 统一前缀：{@code /app/api/v1}；编排 map 路线预估、calculate 计费、order 建单/指派、capacity 派单等。
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
     * {@code POST /app/api/v1/orders}
     */
    @PostMapping("/orders")
    public ResponseVo<CreateAndAssignOrderResult> createAndAssign(
            @RequestHeader(value = USER_ID_HEADER, required = false) Long passengerId,
            @RequestBody @Valid CreateAndAssignOrderBody body) {
        if (passengerId == null) {
            throw new BizErrorException(401, "未授权，请重新登录");
        }
        rejectPassengerIdBodyMismatch(passengerId, body.getPassengerId());
        body.setPassengerId(passengerId);
        return ResultUtil.success(passengerOrderService.createAndAssign(body));
    }

    /**
     * 下单（两段式）：路线/估价 → 创建订单；派单异步推进。
     * {@code POST /app/api/v1/orders/create}
     */
    @PostMapping("/orders/create")
    public ResponseVo<CreateOrderResultV1> createTwoPhase(
            @RequestHeader(value = USER_ID_HEADER, required = false) Long passengerId,
            @RequestBody @Valid CreateAndAssignOrderBody body) {
        if (passengerId == null) {
            throw new BizErrorException(401, "未授权，请重新登录");
        }
        rejectPassengerIdBodyMismatch(passengerId, body.getPassengerId());
        body.setPassengerId(passengerId);
        return ResultUtil.success(passengerOrderService.createTwoPhase(body));
    }

    /**
     * 订单详情（轮询）：校验 {@code passengerId} 与订单乘客一致。
     * {@code GET /app/api/v1/orders/{orderNo}}，请求头 {@code X-User-Id}（经网关由 JWT 注入）。
     */
    @GetMapping("/orders/{orderNo}")
    public ResponseVo<PassengerOrderDetailVO> orderDetail(
            @PathVariable String orderNo,
            @RequestHeader(value = USER_ID_HEADER, required = false) Long passengerId) {
        if (passengerId == null) {
            throw new BizErrorException(401, "未授权，请重新登录");
        }
        return ResultUtil.success(passengerOrderService.getOrderDetail(orderNo, passengerId));
    }

    /**
     * 乘客取消订单：透传 order-service。
     * {@code POST /app/api/v1/orders/{orderNo}/cancel}
     */
    @PostMapping("/orders/{orderNo}/cancel")
    public ResponseVo<Void> cancelOrder(@PathVariable String orderNo,
                                        @RequestHeader(value = USER_ID_HEADER, required = false) Long passengerId,
                                        @RequestBody @Valid CancelOrderRequest body) {
        if (passengerId == null) {
            throw new BizErrorException(401, "未授权，请重新登录");
        }
        rejectPassengerIdBodyMismatch(passengerId, body.getPassengerId());
        body.setPassengerId(passengerId);
        passengerOrderService.cancelOrder(orderNo, body);
        return ResultUtil.success(null);
    }

    /**
     * 请求体若携带 {@code passengerId}，必须与网关注入的 {@code X-User-Id} 一致，否则拒绝（防伪造、防混填）。
     */
    private static void rejectPassengerIdBodyMismatch(Long trustedPassengerId, Long bodyPassengerId) {
        if (bodyPassengerId != null && !bodyPassengerId.equals(trustedPassengerId)) {
            throw new BizErrorException(400, "请求体中的乘客信息与当前登录身份不一致，请勿填写 passengerId");
        }
    }
}

