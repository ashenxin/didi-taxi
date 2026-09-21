package com.sx.map.exception;

/** 地图返回了路线数据，但导航证据不足以证明路线确实经过指定地点。 */
public class AmapRouteEvidenceInsufficientException extends AmapApiException {

    public AmapRouteEvidenceInsufficientException(String message) {
        super(message);
    }
}
