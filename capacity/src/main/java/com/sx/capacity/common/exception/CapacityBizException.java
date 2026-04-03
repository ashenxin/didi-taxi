package com.sx.capacity.common.exception;

/**
 * 业务可预期异常，由 {@link GlobalExceptionHandler} 转为 400 与可读文案。
 */
public class CapacityBizException extends RuntimeException {

    public CapacityBizException(String message) {
        super(message);
    }
}
