package com.sx.map.exception;

import org.springframework.http.HttpStatus;

/**
 * AI 客服路线内部接口的业务异常，携带稳定错误码与真实 HTTP 状态。
 * 内部调用方（passenger-api）从响应头读取稳定错误码，不解析响应体错误码。
 */
public class AiRouteBusinessException extends RuntimeException {

    private final String stableCode;
    private final HttpStatus status;

    public AiRouteBusinessException(String stableCode, HttpStatus status, String message) {
        this(stableCode, status, message, null);
    }

    public AiRouteBusinessException(String stableCode, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.stableCode = stableCode;
        this.status = status;
    }

    public String getStableCode() {
        return stableCode;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
