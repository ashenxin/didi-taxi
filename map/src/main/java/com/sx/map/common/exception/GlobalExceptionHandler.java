package com.sx.map.common.exception;

import com.sx.map.common.enums.ExceptionCode;
import com.sx.map.common.util.ResultUtil;
import com.sx.map.common.vo.ResponseVo;
import com.sx.map.exception.AiRouteBusinessException;
import com.sx.map.exception.AmapApiException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler(AmapApiException.class)
    public ResponseVo<?> amapApiExceptionHandler(AmapApiException e) {
        log.warn("高德API异常：{}", e.getMessage());
        return ResultUtil.requestError(e.getMessage());
    }

    @ExceptionHandler(AiRouteBusinessException.class)
    public ResponseEntity<ResponseVo<?>> aiRouteBusinessExceptionHandler(AiRouteBusinessException e) {
        log.warn("AI 路线业务异常 code={} status={} msg={}",
                e.getStableCode(), e.getStatus().value(), e.getMessage(), e.getCause());
        return ResponseEntity.status(e.getStatus())
                .header("X-Ai-Error-Code", e.getStableCode())
                .body(ResultUtil.error(e.getStatus().value(), e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ResponseVo<?>> illegalArgumentExceptionHandler(IllegalArgumentException e) {
        log.warn("请求参数非法：{}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ResultUtil.error(ExceptionCode.BAD_REQUEST.getValue(), e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseVo<?> exceptionHandler(Exception e) {
        log.error("未处理异常 type={} msg={}", e.getClass().getName(), e.getMessage(), e);
        return ResultUtil.error(ExceptionCode.SERVER_ERROR.getValue(), "服务器繁忙，请稍后重试");
    }
}
