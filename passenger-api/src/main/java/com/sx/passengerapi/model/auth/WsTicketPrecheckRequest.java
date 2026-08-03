package com.sx.passengerapi.model.auth;

import jakarta.validation.constraints.NotBlank;

/** 网关 Upgrade 前传入的 WS 小票；不得记录请求体。 */
public record WsTicketPrecheckRequest(@NotBlank String ticket) {
}
