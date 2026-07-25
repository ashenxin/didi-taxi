package com.sx.wallet.common.util;

import com.sx.wallet.common.vo.ResponseVo;

public final class ResultUtil {
    private ResultUtil() {
    }

    public static <T> ResponseVo<T> success(T data) {
        ResponseVo<T> vo = new ResponseVo<>();
        vo.setCode(200);
        vo.setMsg("success");
        vo.setData(data);
        return vo;
    }

    public static <T> ResponseVo<T> requestError(String msg) {
        return error(400, msg);
    }

    public static <T> ResponseVo<T> error(int code, String msg) {
        ResponseVo<T> vo = new ResponseVo<>();
        vo.setCode(code);
        vo.setMsg(msg);
        return vo;
    }

    public static <T> ResponseVo<T> error(int code, String error, String msg, T data) {
        ResponseVo<T> vo = new ResponseVo<>();
        vo.setCode(code);
        vo.setError(error);
        vo.setMsg(msg);
        vo.setData(data);
        return vo;
    }
}
