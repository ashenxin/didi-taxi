package com.sx.driverapi.model.ordercore;

/** 接单前置幂等检查结果；重放时 BFF 不再重复调用运力资格校验。 */
public record AcceptOrderPreflightResult(boolean replayed) {
}
