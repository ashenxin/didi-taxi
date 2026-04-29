package com.sx.passengerapi.model.auth;

import lombok.Data;

/**
 * {@code POST /app/api/v1/auth/logout} 响应 data；{@code hint} 为可选的用户提示（与 PRD §5.6 对齐）。
 */
@Data
public class PassengerLogoutResult {
    /** 可选：如到达前已代取消订单、或到达后未取消订单等说明 */
    private String hint;
}
