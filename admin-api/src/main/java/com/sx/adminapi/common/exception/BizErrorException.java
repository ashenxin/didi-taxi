package com.sx.adminapi.common.exception;


import com.sx.adminapi.common.enums.ExceptionCode;

public class BizErrorException extends RuntimeException {

    /**
     * 错误码
     */
    private final Integer errorCode;

    private final String errorMessage;

    public BizErrorException(ExceptionCode exceptionCode) {
        super(exceptionCode.getValue() + " : " + exceptionCode.getName());
        this.errorCode = exceptionCode.getValue();
        this.errorMessage = exceptionCode.getName();
    }

    public BizErrorException(int errorCode, String errorMessage) {
        super(errorCode + " : " + errorMessage);
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
