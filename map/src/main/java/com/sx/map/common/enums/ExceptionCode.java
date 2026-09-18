package com.sx.map.common.enums;

public enum ExceptionCode implements NameValueEnum<Integer> {
    SUCCESS(200, "success"),
    BAD_REQUEST(400, "bad request"),
    UNAUTHORIZED(401, "Unauthorized"),
    FORBIDDEN(403, "Forbidden"),
    NOT_FOUND(404, "Not Found"),
    CONFLICT(409, "Conflict"),
    GATEWAY_TIMEOUT(504, "Gateway Timeout"),
    SERVER_ERROR(500, "服务异常");

    private final Integer code;
    private final String msg;

    ExceptionCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public String getName() {
        return msg;
    }

    @Override
    public Integer getValue() {
        return code;
    }
}

