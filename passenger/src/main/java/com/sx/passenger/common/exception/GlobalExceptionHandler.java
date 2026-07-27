package com.sx.passenger.common.exception;

import com.sx.passenger.auth.session.AuthEpochConflictException;
import com.sx.passenger.common.enums.ExceptionCode;
import com.sx.passenger.common.util.ResultUtil;
import com.sx.passenger.common.vo.ResponseVo;
import com.sx.passenger.lifecycle.application.LifecycleOperationConflictException;
import com.sx.passenger.lifecycle.api.LifecyclePrecheckUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static java.util.stream.Collectors.joining;

@Validated
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @ExceptionHandler(LifecyclePrecheckUnavailableException.class)
    public ResponseEntity<ResponseVo<?>> lifecyclePrecheckUnavailable(
            LifecyclePrecheckUnavailableException ignored, HttpServletRequest request) {
        log.error("account lifecycle precheck unavailable uri={} requestId={} status={}",
                request.getRequestURI(), request.getHeader(REQUEST_ID_HEADER), 503);
        return ResponseEntity.status(503)
                .body(ResultUtil.error(ExceptionCode.SERVICE_UNAVAILABLE.getValue(),
                        "注销风险检查暂不可用，请稍后重试"));
    }

    @ExceptionHandler(LifecycleOperationConflictException.class)
    public ResponseEntity<ResponseVo<?>> lifecycleConflict(
            LifecycleOperationConflictException ignored, HttpServletRequest request) {
        log.warn("account lifecycle conflict uri={} requestId={} status={}",
                request.getRequestURI(), request.getHeader(REQUEST_ID_HEADER), 409);
        return ResponseEntity.status(409)
                .body(ResultUtil.error(ExceptionCode.CONFLICT.getValue(), "账号状态已变化，请刷新后重试"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ResponseVo<?>> lifecycleBadRequest(
            IllegalArgumentException ignored, HttpServletRequest request) {
        log.warn("bad request uri={} requestId={} status={}",
                request.getRequestURI(), request.getHeader(REQUEST_ID_HEADER), 400);
        return ResponseEntity.badRequest()
                .body(ResultUtil.error(ExceptionCode.BAD_REQUEST.getValue(), "请求参数有误，请检查后重试"));
    }

    @ExceptionHandler(AuthEpochConflictException.class)
    public ResponseEntity<ResponseVo<?>> authEpochConflictException(
            AuthEpochConflictException ignored, HttpServletRequest request) {
        log.warn("internal auth request rejected uri={} requestId={} status={}",
                request.getRequestURI(), request.getHeader(REQUEST_ID_HEADER), 409);
        return ResponseEntity.status(409)
                .body(ResultUtil.error(ExceptionCode.CONFLICT.getValue(), "认证状态已变化，请刷新后重试"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ResponseVo<?>> dataAccessException(
            DataAccessException ignored, HttpServletRequest request) {
        log.error("infrastructure unavailable uri={} requestId={} status={}",
                request.getRequestURI(), request.getHeader(REQUEST_ID_HEADER), 503);
        return ResponseEntity.status(503)
                .body(ResultUtil.error(ExceptionCode.SERVICE_UNAVAILABLE.getValue(), "服务暂不可用，请稍后重试"));
    }

    @ExceptionHandler(AdminPermissionException.class)
    public ResponseEntity<ResponseVo<?>> adminPermissionException(AdminPermissionException e) {
        log.warn("管理端权限异常：{}", e.getMessage());
        return ResponseEntity.status(403).body(ResultUtil.forbidden(e.getMessage()));
    }

    @ExceptionHandler(AdminResourceNotFoundException.class)
    public ResponseEntity<ResponseVo<?>> adminResourceNotFoundException(AdminResourceNotFoundException e) {
        log.warn("管理端资源不存在：{}", e.getMessage());
        return ResponseEntity.status(404).body(ResultUtil.error(ExceptionCode.NOT_FOUND.getValue(), e.getMessage()));
    }

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

    @ExceptionHandler(Exception.class)
    public ResponseVo<?> exceptionHandler(Exception e) {
        log.error("未处理异常 type={} msg={}", e.getClass().getName(), e.getMessage(), e);
        return ResultUtil.error(ExceptionCode.SERVER_ERROR.getValue(), "服务器繁忙，请稍后重试");
    }
}
