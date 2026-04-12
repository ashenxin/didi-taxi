package com.sx.map.common.exception;

import com.sx.map.common.enums.ExceptionCode;
import com.sx.map.common.util.ResultUtil;
import com.sx.map.common.vo.ResponseVo;
import com.sx.map.exception.AmapApiException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
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
public class GlobalExceptionHandler {

    @ExceptionHandler(BindException.class)
    public ResponseVo<?> bindExceptionHandler(BindException e) {
        final String errMsg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage).collect(joining(", "));
        return ResultUtil.requestError(errMsg);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseVo<?> methodArgumentNotValidException(MethodArgumentNotValidException e) {
        final String errMsg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage).collect(joining(", "));
        return ResultUtil.requestError(errMsg);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseVo<?> missingServletRequestParameterExceptionHandler(MissingServletRequestParameterException e) {
        return ResultUtil.requestError(String.format("参数[%s]不能为空", e.getParameterName()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseVo<?> constraintViolationExceptionHandler(ConstraintViolationException e) {
        final String errMsg = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(joining(", "));
        return ResultUtil.requestError(errMsg);
    }

    @ExceptionHandler(AmapApiException.class)
    public ResponseVo<?> amapApiExceptionHandler(AmapApiException e) {
        return ResultUtil.requestError(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ResponseVo<?> exceptionHandler(Exception e) {
        return ResultUtil.error(ExceptionCode.SERVER_ERROR.getValue(), "抛出的异常:" + e.getClass().getSimpleName());
    }
}

