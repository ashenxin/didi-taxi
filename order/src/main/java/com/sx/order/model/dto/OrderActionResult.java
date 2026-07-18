package com.sx.order.model.dto;

/** 订单写操作结果；{@code replayed=true} 表示命中已成功的同一幂等请求。 */
public record OrderActionResult(boolean replayed) {

    public static OrderActionResult executed() {
        return new OrderActionResult(false);
    }

    public static OrderActionResult replayedResult() {
        return new OrderActionResult(true);
    }
}
