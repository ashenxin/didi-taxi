package com.sx.order.model.dto;

/**
 * 从「已分配」进入「待司机确认」窗口（由 BFF 下单成功后调用）。
 */
public class OpenDriverOfferBody {

    /**
     * 确认窗口秒数，默认 30；服务端据此计算 {@code offer_expires_at}（与 capacity/passenger-api 配置保持一致）。
     */
    private int offerSeconds = 30;

    public int getOfferSeconds() {
        return offerSeconds;
    }

    public void setOfferSeconds(int offerSeconds) {
        this.offerSeconds = offerSeconds;
    }
}
