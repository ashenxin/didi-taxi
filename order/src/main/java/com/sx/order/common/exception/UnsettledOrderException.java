package com.sx.order.common.exception;

import com.sx.order.model.dto.BlockingOrderResult;

public class UnsettledOrderException extends RuntimeException {
    private final BlockingOrderResult result;

    public UnsettledOrderException(String message, BlockingOrderResult result) {
        super(message);
        this.result = result;
    }

    public BlockingOrderResult getResult() {
        return result;
    }
}
