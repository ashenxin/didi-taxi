package com.sx.passengerapi.controller;

import com.sx.passengerapi.common.util.ResultUtil;
import com.sx.passengerapi.common.vo.ResponseVo;
import com.sx.passengerapi.model.order.OrderChangedNotifyBody;
import com.sx.passengerapi.ws.PassengerWsNotifyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部通知入口：订单服务 / 司机 BFF 在订单状态变更后通知乘客端刷新订单详情。
 * 统一前缀：{@code /app/internal/v1}；通知只携带轻量变更信号，HTTP 订单详情仍为展示权威。
 */
@RestController
@RequestMapping("/app/internal/v1")
public class PassengerInternalNotifyController {

    private final PassengerWsNotifyService passengerWsNotifyService;

    public PassengerInternalNotifyController(PassengerWsNotifyService passengerWsNotifyService) {
        this.passengerWsNotifyService = passengerWsNotifyService;
    }

    /**
     * 向指定乘客推送 {@code ORDER_CHANGED} 事件，提示客户端按订单号重新拉取详情。
     * {@code POST /app/internal/v1/orders/changed}
     */
    @PostMapping("/orders/changed")
    public ResponseVo<Void> orderChanged(@RequestBody @Valid OrderChangedNotifyBody body) {
        passengerWsNotifyService.notifyOrderChanged(body.getPassengerId(), body.getOrderNo());
        return ResultUtil.success(null);
    }
}
