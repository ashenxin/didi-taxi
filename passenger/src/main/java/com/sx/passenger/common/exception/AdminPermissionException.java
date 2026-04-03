package com.sx.passenger.common.exception;

/** 后台对内接口：无权限或越权 */
public class AdminPermissionException extends RuntimeException {

    public AdminPermissionException(String message) {
        super(message);
    }
}
