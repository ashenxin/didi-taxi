package com.sx.order.lifecycle.controller;

import com.sx.order.common.util.ResultUtil;
import com.sx.order.common.vo.ResponseVo;
import com.sx.order.lifecycle.model.OrderLifecycleCommand;
import com.sx.order.lifecycle.model.OrderLifecycleParticipantResult;
import com.sx.order.lifecycle.model.OrderLifecyclePrecheckRequest;
import com.sx.order.lifecycle.service.AccountLifecycleOrderParticipantService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/account-lifecycle/order")
public class AccountLifecycleOrderParticipantController {
    private final AccountLifecycleOrderParticipantService participant;

    public AccountLifecycleOrderParticipantController(AccountLifecycleOrderParticipantService participant) {
        this.participant = participant;
    }

    @PostMapping("/precheck")
    public ResponseVo<OrderLifecycleParticipantResult> precheck(
            @RequestBody OrderLifecyclePrecheckRequest request) {
        return ResultUtil.success(participant.precheck(request));
    }

    @PostMapping("/fence")
    public ResponseVo<OrderLifecycleParticipantResult> fence(@RequestBody OrderLifecycleCommand command) {
        return ResultUtil.success(participant.fence(command));
    }

    @GetMapping("/results/{operationNo}/{stepCode}")
    public ResponseVo<OrderLifecycleParticipantResult> result(
            @PathVariable String operationNo, @PathVariable String stepCode) {
        OrderLifecycleParticipantResult result = participant.findResult(operationNo, stepCode);
        return result == null
                ? ResultUtil.error(404, "LIFECYCLE_RESULT_NOT_FOUND",
                        "Order生命周期参与者结果不存在", null)
                : ResultUtil.success(result);
    }
}
