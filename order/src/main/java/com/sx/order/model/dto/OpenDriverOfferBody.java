package com.sx.order.model.dto;

/**
 * 从「已分配」进入「待司机确认」窗口（由 BFF 下单成功后调用）。
 */
public class OpenDriverOfferBody {

    /**
     * 确认窗口秒数，默认 10；服务端据此计算 {@code offer_expires_at}。
     */
    private int offerSeconds = 10;

    public int getOfferSeconds() {
        return offerSeconds;
    }

    public void setOfferSeconds(int offerSeconds) {
        this.offerSeconds = offerSeconds;
    }
}
