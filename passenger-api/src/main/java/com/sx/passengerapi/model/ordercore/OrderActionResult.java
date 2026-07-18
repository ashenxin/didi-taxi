package com.sx.passengerapi.model.ordercore;

/** 订单写操作结果；{@code replayed=true} 表示返回的是同一请求的成功重放。 */
public record OrderActionResult(boolean replayed) {
}
