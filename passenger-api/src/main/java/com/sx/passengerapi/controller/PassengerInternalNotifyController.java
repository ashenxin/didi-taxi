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
 */
@RestController
@RequestMapping("/app/internal/v1")
public class PassengerInternalNotifyController {

    private final PassengerWsNotifyService passengerWsNotifyService;

    public PassengerInternalNotifyController(PassengerWsNotifyService passengerWsNotifyService) {
        this.passengerWsNotifyService = passengerWsNotifyService;
    }

    @PostMapping("/orders/changed")
    public ResponseVo<Void> orderChanged(@RequestBody @Valid OrderChangedNotifyBody body) {
        passengerWsNotifyService.notifyOrderChanged(body.getPassengerId(), body.getOrderNo());
        return ResultUtil.success(null);
    }
}
