package com.sx.driverapi.common.exception;

public class BizErrorException extends RuntimeException {
    private Integer errorCode;
    private String errorMessage;

    public BizErrorException(Integer errorCode, String errorMessage) {
        super(errorMessage);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }

    public Integer getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}

