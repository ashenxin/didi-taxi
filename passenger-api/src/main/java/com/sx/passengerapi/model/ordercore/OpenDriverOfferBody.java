package com.sx.passengerapi.model.ordercore;

/**
 * 打开司机确认窗口（透传 order-service）。
 */
public class OpenDriverOfferBody {

    private int offerSeconds = 30;

    public int getOfferSeconds() {
        return offerSeconds;
    }

    public void setOfferSeconds(int offerSeconds) {
        this.offerSeconds = offerSeconds;
    }
}
