package com.sx.order.model.dto;

/** 接单前置幂等检查；replayed=true 时 BFF 应跳过运力资格检查和正式接单调用。 */
public record AcceptOrderPreflightResult(boolean replayed) {
}
