package com.sx.passengerapi.common.exception;

import com.sx.passengerapi.common.enums.ExceptionCode;
import com.sx.passengerapi.common.util.ResultUtil;
import com.sx.passengerapi.common.vo.ResponseVo;
import feign.FeignException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

import static java.util.stream.Collectors.joining;

@Validated
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static HttpStatus toHttpStatus(Integer code) {
        if (code == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        try {
            return HttpStatus.valueOf(code);
        } catch (IllegalArgumentException ex) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
    }

    private static ResponseEntity<ResponseVo<?>> entity(ResponseVo<?> body) {
        return ResponseEntity.status(toHttpStatus(body.getCode())).body(body);
    }

    @ExceptionHandler(BizErrorException.class)
    public ResponseEntity<ResponseVo<?>> bizErrorExceptionHandler(BizErrorException e, HttpServletRequest request) {
        log.warn("BizError path={} code={} msg={}", request.getRequestURI(), e.getErrorCode(), e.getErrorMessage());
        return entity(ResultUtil.bizError(e));
    }

    @ExceptionHandler(HttpClientErrorException.NotFound.class)
    public ResponseEntity<ResponseVo<?>> notFoundExceptionHandler(HttpClientErrorException.NotFound e, HttpServletRequest request) {
        log.warn("Downstream 404 path={} detail={}", request.getRequestURI(), e.getMessage());
        return entity(ResultUtil.error(ExceptionCode.NOT_FOUND.getValue(), "资源不存在"));
    }

    @ExceptionHandler(FeignException.NotFound.class)
    public ResponseEntity<ResponseVo<?>> feignNotFoundExceptionHandler(FeignException.NotFound e, HttpServletRequest request) {
        log.warn("Downstream 404(feign) path={} detail={}", request.getRequestURI(), e.getMessage());
        return entity(ResultUtil.error(ExceptionCode.NOT_FOUND.getValue(), "资源不存在"));
    }

    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<ResponseVo<?>> httpClientErrorExceptionHandler(HttpClientErrorException e, HttpServletRequest request) {
        log.warn("Downstream 4xx path={} status={} detail={}", request.getRequestURI(), e.getStatusCode(), e.getMessage());
        if (e.getStatusCode().value() == 400) {
            return entity(ResultUtil.requestError("请求参数有误，请检查后重试"));
        }
        if (e.getStatusCode().value() == 401) {
            return entity(ResultUtil.error(ExceptionCode.UNAUTHORIZED.getValue(), "未授权，请重新登录"));
        }
        if (e.getStatusCode().value() == 403) {
            return entity(ResultUtil.error(ExceptionCode.FORBIDDEN.getValue(), "暂无权限访问该资源"));
        }
        if (e.getStatusCode().value() == 404) {
            return entity(ResultUtil.error(ExceptionCode.NOT_FOUND.getValue(), "资源不存在"));
        }
        return entity(ResultUtil.error(ExceptionCode.BAD_GATEWAY.getValue(), "服务暂时不可用，请稍后重试"));
    }

    @ExceptionHandler(FeignException.class)
    public ResponseEntity<ResponseVo<?>> feignExceptionHandler(FeignException e, HttpServletRequest request) {
        int status = e.status();
        log.warn("Downstream feign error path={} status={} detail={}", request.getRequestURI(), status, e.getMessage());
        if (status == 400) {
            return entity(ResultUtil.requestError("请求参数有误，请检查后重试"));
        }
        if (status == 401) {
            return entity(ResultUtil.error(ExceptionCode.UNAUTHORIZED.getValue(), "未授权，请重新登录"));
        }
        if (status == 403) {
            return entity(ResultUtil.error(ExceptionCode.FORBIDDEN.getValue(), "暂无权限访问该资源"));
        }
        if (status == 404) {
            return entity(ResultUtil.error(ExceptionCode.NOT_FOUND.getValue(), "资源不存在"));
        }
        if (status == 504) {
            return entity(ResultUtil.error(ExceptionCode.GATEWAY_TIMEOUT.getValue(), "下游服务响应超时，请稍后重试"));
        }
        if (status >= 500) {
            return entity(ResultUtil.error(ExceptionCode.BAD_GATEWAY.getValue(), "服务暂时不可用，请稍后重试"));
        }
        return entity(ResultUtil.error(ExceptionCode.BAD_GATEWAY.getValue(), "服务暂时不可用，请稍后重试"));
    }

    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<ResponseVo<?>> httpServerErrorExceptionHandler(HttpServerErrorException e, HttpServletRequest request) {
        int s = e.getStatusCode().value();
        log.error("Downstream 5xx path={} status={} detail={}", request.getRequestURI(), e.getStatusCode(), e.getMessage(), e);
        if (s == 504) {
            return entity(ResultUtil.error(ExceptionCode.GATEWAY_TIMEOUT.getValue(), "下游服务响应超时，请稍后重试"));
        }
        return entity(ResultUtil.error(ExceptionCode.BAD_GATEWAY.getValue(), "服务暂时不可用，请稍后重试"));
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ResponseVo<?>> resourceAccessExceptionHandler(ResourceAccessException e, HttpServletRequest request) {
        log.error("Downstream access error path={} detail={}", request.getRequestURI(), e.getMessage(), e);
        return entity(ResultUtil.error(ExceptionCode.GATEWAY_TIMEOUT.getValue(), "服务连接超时或不可达，请稍后重试"));
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<ResponseVo<?>> restClientExceptionHandler(RestClientException e, HttpServletRequest request) {
        log.error("Downstream rest client error path={} detail={}", request.getRequestURI(), e.getMessage(), e);
        return entity(ResultUtil.error(ExceptionCode.BAD_GATEWAY.getValue(), "服务调用异常，请稍后重试"));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ResponseVo<?>> bindExceptionHandler(BindException e, HttpServletRequest request) {
        final String errMsg = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage).collect(joining(", "));
        log.warn("BindException path={} msg={}", request.getRequestURI(), errMsg);
        return entity(ResultUtil.requestError(errMsg));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ResponseVo<?>> methodArgumentNotValidException(MethodArgumentNotValidException e, HttpServletRequest request) {
        final String errMsg = e.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(FieldError::getDefaultMessage).collect(joining(", "));
        log.warn("MethodArgumentNotValid path={} msg={}", request.getRequestURI(), errMsg);
        return entity(ResultUtil.requestError(errMsg));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ResponseVo<?>> missingServletRequestParameterExceptionHandler(MissingServletRequestParameterException e, HttpServletRequest request) {
        String errMsg = String.format("参数[%s]不能为空", e.getParameterName());
        log.warn("MissingParam path={} msg={}", request.getRequestURI(), errMsg);
        return entity(ResultUtil.requestError(errMsg));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ResponseVo<?>> constraintViolationExceptionHandler(ConstraintViolationException e, HttpServletRequest request) {
        final String errMsg = e.getConstraintViolations()
                .stream()
                .map(ConstraintViolation::getMessage)
                .collect(joining(", "));
        log.warn("ConstraintViolation path={} msg={}", request.getRequestURI(), errMsg);
        return entity(ResultUtil.requestError(errMsg));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ResponseVo<?>> illegalArgumentExceptionHandler(IllegalArgumentException e, HttpServletRequest request) {
        log.warn("IllegalArgument path={} detail={}", request.getRequestURI(), e.getMessage(), e);
        return entity(ResultUtil.requestError("请求参数有误，请检查后重试"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ResponseVo<?>> exceptionHandler(Exception e, HttpServletRequest request) {
        log.error("Unhandled exception path={} type={} detail={}",
                request.getRequestURI(), e.getClass().getSimpleName(), e.getMessage(), e);
        return entity(ResultUtil.error(ExceptionCode.SERVER_ERROR.getValue(), "页面异常，请稍后重试"));
    }
}

