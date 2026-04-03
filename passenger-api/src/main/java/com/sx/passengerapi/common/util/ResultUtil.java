package com.sx.passengerapi.common.util;

import com.sx.passengerapi.common.enums.ExceptionCode;
import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.common.vo.ResponseVo;

public class ResultUtil {
    public static <T> ResponseVo<T> success(T data) {
        return new ResponseVo<>(ExceptionCode.SUCCESS.getValue(), ExceptionCode.SUCCESS.getName(), data);
    }

    public static <T> ResponseVo<T> success() {
        return success(null);
    }

    public static <T> ResponseVo<T> error(Integer errorCode, String msg) {
        return new ResponseVo<>(errorCode, msg, null);
    }

    public static <T> ResponseVo<T> bizError(BizErrorException e) {
        return error(e.getErrorCode(), e.getErrorMessage());
    }

    public static <T> ResponseVo<T> requestError(String msg) {
        return error(ExceptionCode.BAD_REQUEST.getValue(), msg);
    }
}

