package com.sx.map.exception;

/**
 * 高德路径规划返回失败或本地校验失败时抛出，由全局异常处理转为业务错误信息。
 */
public class AmapApiException extends RuntimeException {

    public AmapApiException(String message) {
        super(message);
    }
}
