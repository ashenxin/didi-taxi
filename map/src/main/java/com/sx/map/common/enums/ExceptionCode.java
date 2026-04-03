package com.sx.map.common.enums;

public enum ExceptionCode implements NameValueEnum<Integer> {
    SUCCESS(200, "success"),
    BAD_REQUEST(400, "bad request"),
    NOT_FOUND(404, "Not Found"),
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

