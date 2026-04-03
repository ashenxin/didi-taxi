package com.sx.calculate.common.util;

import com.sx.calculate.common.enums.ExceptionCode;
import com.sx.calculate.common.vo.ResponseVo;

public class ResultUtil {
    public static <T> ResponseVo<T> success(T data) {
        return ResponseVo.success(data);
    }

    public static <T> ResponseVo<T> error(Integer errorCode, String msg) {
        return new ResponseVo<>(errorCode, msg, null);
    }

    public static <T> ResponseVo<T> requestError(String msg) {
        return error(ExceptionCode.BAD_REQUEST.getValue(), msg);
    }
}
