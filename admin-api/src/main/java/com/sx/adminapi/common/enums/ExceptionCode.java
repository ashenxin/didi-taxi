package com.sx.adminapi.common.enums;

/**
 * 通用异常参数枚举
 */
public enum ExceptionCode implements NameValueEnum<Integer> {

    SUCCESS(200, "success"),
    BAD_REQUEST(400, "bad request"),
    UNAUTHORIZED(401, "unauthorized"),
    FORBIDDEN(403, "forbidden"),
    NOT_FOUND(404, "Not Found"),
    SERVER_ERROR(500, "服务异常"),
    /** 下游服务返回错误或不可用（BFF 视角） */
    BAD_GATEWAY(502, "bad gateway"),
    /** 下游连接超时或不可达 */
    GATEWAY_TIMEOUT(504, "gateway timeout");

    /**
     * 错误信息code码
     */
    private final Integer code;

    /**
     * 错误信息
     */
    private final String msg;


    ExceptionCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }


    @Override
    public String getName() {
        return this.msg;
    }

    @Override
    public Integer getValue() {
        return this.code;
    }
}
