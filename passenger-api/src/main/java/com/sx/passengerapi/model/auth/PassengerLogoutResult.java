package com.sx.passengerapi.model.auth;

import lombok.Data;

/**
 * {@code POST /app/api/v1/auth/logout} 响应 data；{@code hint} 为可选的用户提示（与 PRD §5.6 对齐）。
 */
@Data
public class PassengerLogoutResult {
    /** core 认证代次已提交失效，后续订单清理失败也保持 true。 */
    private boolean loggedOut;
    /** 订单清理暂未完成，客户端可稍后查询或由后台重试。 */
    private boolean orderCleanupPending;
    /** 可选：如到达前已代取消订单、或到达后未取消订单等说明 */
    private String hint;

    public static PassengerLogoutResult loggedOutWithPendingCleanup(String hint) {
        PassengerLogoutResult result = new PassengerLogoutResult();
        result.setLoggedOut(true);
        result.setOrderCleanupPending(true);
        result.setHint(hint);
        return result;
    }
}
