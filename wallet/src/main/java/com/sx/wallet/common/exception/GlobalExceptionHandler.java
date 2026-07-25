package com.sx.wallet.common.exception;

import com.sx.wallet.common.util.ResultUtil;
import com.sx.wallet.common.vo.ResponseVo;
import com.sx.wallet.lifecycle.exception.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseVo<?> badRequest(IllegalArgumentException ex) {
        return ResultUtil.error(400, "INVALID_REQUEST", ex.getMessage(), null);
    }

    @ExceptionHandler(WalletLifecycleBlockedException.class)
    public ResponseVo<?> blocked(WalletLifecycleBlockedException ex) {
        return ResultUtil.error(409, "ACCOUNT_LIFECYCLE_BLOCKED", ex.getMessage(), null);
    }

    @ExceptionHandler(WalletLifecycleCommandConflictException.class)
    public ResponseVo<?> commandConflict(WalletLifecycleCommandConflictException ex) {
        return ResultUtil.error(409, "LIFECYCLE_COMMAND_CONFLICT", ex.getMessage(), null);
    }

    @ExceptionHandler(WalletLifecycleProjectionConflictException.class)
    public ResponseVo<?> projectionConflict(WalletLifecycleProjectionConflictException ex) {
        return ResultUtil.error(409, "LIFECYCLE_PROJECTION_CONFLICT", ex.getMessage(), null);
    }

    @ExceptionHandler({WalletLifecycleUnknownException.class,
            WalletLifecycleParticipantUnavailableException.class})
    public ResponseVo<?> unknown(RuntimeException ex) {
        log.error("Wallet生命周期状态未知", ex);
        return ResultUtil.error(503, "ACCOUNT_LIFECYCLE_UNKNOWN",
                "Wallet生命周期参与者暂时不可用", null);
    }
}
