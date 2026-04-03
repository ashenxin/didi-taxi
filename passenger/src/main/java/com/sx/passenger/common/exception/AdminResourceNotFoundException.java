package com.sx.passenger.common.exception;

/** 后台对内接口：资源不存在 */
public class AdminResourceNotFoundException extends RuntimeException {

    public AdminResourceNotFoundException(String message) {
        super(message);
    }
}
