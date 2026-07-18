package com.sx.driverapi.model.ordercore;

/** order-service 司机写操作响应；replayed 表示命中已成功的同一幂等请求。 */
public record DriverActionResult(boolean replayed) {
}
