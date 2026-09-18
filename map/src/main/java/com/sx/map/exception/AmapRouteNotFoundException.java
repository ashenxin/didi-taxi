package com.sx.map.exception;

/** 高德成功响应但没有可用驾车路线，与地图服务故障区分。 */
public class AmapRouteNotFoundException extends AmapApiException {

    public AmapRouteNotFoundException(String message) {
        super(message);
    }
}
