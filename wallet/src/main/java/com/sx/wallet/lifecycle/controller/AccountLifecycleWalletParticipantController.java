package com.sx.wallet.lifecycle.controller;

import com.sx.wallet.common.util.ResultUtil;
import com.sx.wallet.common.vo.ResponseVo;
import com.sx.wallet.lifecycle.model.WalletLifecycleCommand;
import com.sx.wallet.lifecycle.model.WalletLifecycleParticipantResult;
import com.sx.wallet.lifecycle.model.WalletLifecyclePrecheckRequest;
import com.sx.wallet.lifecycle.model.WalletManualResolutionRequest;
import com.sx.wallet.lifecycle.service.AccountLifecycleWalletParticipantService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/internal/account-lifecycle/wallet")
public class AccountLifecycleWalletParticipantController {
    private final AccountLifecycleWalletParticipantService participant;

    public AccountLifecycleWalletParticipantController(
            AccountLifecycleWalletParticipantService participant) {
        this.participant = participant;
    }

    @PostMapping("/precheck")
    public ResponseVo<WalletLifecycleParticipantResult> precheck(
            @RequestBody WalletLifecyclePrecheckRequest request) {
        return ResultUtil.success(participant.precheck(request.customerId()));
    }

    @PostMapping("/fence")
    public ResponseVo<WalletLifecycleParticipantResult> fence(
            @RequestBody WalletLifecycleCommand command) {
        return ResultUtil.success(participant.fence(command));
    }

    @PostMapping("/actions")
    public ResponseVo<WalletLifecycleParticipantResult> action(
            @RequestBody WalletLifecycleCommand command) {
        return ResultUtil.success(participant.action(command));
    }

    @GetMapping("/results/{operationNo}/{stepCode}")
    public ResponseVo<WalletLifecycleParticipantResult> result(
            @PathVariable String operationNo, @PathVariable String stepCode) {
        WalletLifecycleParticipantResult result = participant.findResult(operationNo, stepCode);
        return result == null ? ResultUtil.error(404, "LIFECYCLE_RESULT_NOT_FOUND",
                "Wallet生命周期参与者结果不存在", null) : ResultUtil.success(result);
    }

    @PostMapping("/manual-resolutions")
    public ResponseVo<WalletLifecycleParticipantResult> resolve(
            @RequestBody WalletManualResolutionRequest request) {
        return ResultUtil.success(participant.resolveManually(request));
    }
}
