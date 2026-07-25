package com.sx.calculate.common.exception;

import com.sx.calculate.common.enums.ExceptionCode;
import com.sx.calculate.common.util.ResultUtil;
import com.sx.calculate.common.vo.ResponseVo;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleBlockedException;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleCommandConflictException;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleParticipantUnavailableException;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleProjectionConflictException;
import com.sx.calculate.lifecycle.exception.CalculateLifecycleUnknownException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static java.util.stream.Collectors.joining;

@Validated
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    @ExceptionHandler(BindException.class)
    public ResponseVo<?> bindExceptionHandler(BindException e) {
        final String errMsg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage).collect(joining(", "));
        log.warn("参数绑定异常：{}", errMsg);
        return ResultUtil.requestError(errMsg);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseVo<?> methodArgumentNotValidException(MethodArgumentNotValidException e) {
        final String errMsg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage).collect(joining(", "));
        log.warn("请求体校验失败：{}", errMsg);
        return ResultUtil.requestError(errMsg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseVo<?> missingServletRequestParameterExceptionHandler(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数：{}", e.getParameterName());
        return ResultUtil.requestError(String.format("参数[%s]不能为空", e.getParameterName()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseVo<?> constraintViolationExceptionHandler(ConstraintViolationException e) {
        final String errMsg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(joining(", "));
        log.warn("约束校验失败：{}", errMsg);
        return ResultUtil.requestError(errMsg);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseVo<?> illegalArgumentExceptionHandler(IllegalArgumentException e) {
        log.warn("非法参数：{}", e.getMessage());
        return ResultUtil.requestError(e.getMessage());
    }

    @ExceptionHandler(CalculateLifecycleBlockedException.class)
    public ResponseVo<?> lifecycleBlocked(CalculateLifecycleBlockedException e) {
        log.warn("Calculate生命周期阻止写入：{}", e.getMessage());
        return ResultUtil.error(409, "ACCOUNT_LIFECYCLE_BLOCKED", e.getMessage(), null);
    }

    @ExceptionHandler(CalculateLifecycleCommandConflictException.class)
    public ResponseVo<?> lifecycleCommandConflict(CalculateLifecycleCommandConflictException e) {
        log.warn("Calculate生命周期命令冲突：{}", e.getMessage());
        return ResultUtil.error(409, "LIFECYCLE_COMMAND_CONFLICT", e.getMessage(), null);
    }

    @ExceptionHandler(CalculateLifecycleProjectionConflictException.class)
    public ResponseVo<?> lifecycleProjectionConflict(CalculateLifecycleProjectionConflictException e) {
        log.warn("Calculate生命周期投影冲突：{}", e.getMessage());
        return ResultUtil.error(409, "LIFECYCLE_PROJECTION_CONFLICT", e.getMessage(), null);
    }

    @ExceptionHandler({CalculateLifecycleUnknownException.class,
            CalculateLifecycleParticipantUnavailableException.class})
    public ResponseVo<?> lifecycleUnknown(RuntimeException e) {
        log.error("Calculate生命周期基础设施状态未知 type={}", e.getClass().getName(), e);
        return ResultUtil.error(503, "ACCOUNT_LIFECYCLE_UNKNOWN",
                "Calculate生命周期参与者暂时不可用", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseVo<?> exceptionHandler(Exception e) {
        log.error("未处理异常 type={} msg={}", e.getClass().getName(), e.getMessage(), e);
        return ResultUtil.error(ExceptionCode.SERVER_ERROR.getValue(), "服务器繁忙，请稍后重试");
    }
}
