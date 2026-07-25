package com.sx.calculate.lifecycle.controller;

import com.sx.calculate.common.util.ResultUtil;
import com.sx.calculate.common.vo.ResponseVo;
import com.sx.calculate.lifecycle.model.CalculateLifecycleCommand;
import com.sx.calculate.lifecycle.model.CalculateLifecycleParticipantResult;
import com.sx.calculate.lifecycle.model.CalculateLifecyclePrecheckRequest;
import com.sx.calculate.lifecycle.service.AccountLifecycleCalculateParticipantService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/internal/account-lifecycle/calculate")
public class AccountLifecycleCalculateParticipantController {
    private final AccountLifecycleCalculateParticipantService participant;

    public AccountLifecycleCalculateParticipantController(
            AccountLifecycleCalculateParticipantService participant) {
        this.participant = participant;
    }

    @PostMapping("/precheck")
    public ResponseVo<CalculateLifecycleParticipantResult> precheck(
            @RequestBody CalculateLifecyclePrecheckRequest request) {
        return ResultUtil.success(participant.precheck(request.customerId()));
    }

    @PostMapping("/fence")
    public ResponseVo<CalculateLifecycleParticipantResult> fence(
            @RequestBody CalculateLifecycleCommand command) {
        return ResultUtil.success(participant.fence(command));
    }

    @PostMapping("/actions")
    public ResponseVo<CalculateLifecycleParticipantResult> action(
            @RequestBody CalculateLifecycleCommand command) {
        return ResultUtil.success(participant.action(command));
    }

    @GetMapping("/results/{operationNo}/{stepCode}")
    public ResponseVo<CalculateLifecycleParticipantResult> result(
            @PathVariable String operationNo, @PathVariable String stepCode) {
        CalculateLifecycleParticipantResult result = participant.findResult(operationNo, stepCode);
        return result == null
                ? ResultUtil.error(404, "LIFECYCLE_RESULT_NOT_FOUND",
                        "Calculate生命周期参与者结果不存在", null)
                : ResultUtil.success(result);
    }
}
