package com.sx.passengerapi.model.wallet;

public class WalletSummaryVO {
    private int activeAutoPayCount;
    private AutoPayAgreementVO defaultAutoPayAgreement;
    private long availableCouponCount;

    public int getActiveAutoPayCount() {
        return activeAutoPayCount;
    }

    public void setActiveAutoPayCount(int activeAutoPayCount) {
        this.activeAutoPayCount = activeAutoPayCount;
    }

    public AutoPayAgreementVO getDefaultAutoPayAgreement() {
        return defaultAutoPayAgreement;
    }

    public void setDefaultAutoPayAgreement(AutoPayAgreementVO defaultAutoPayAgreement) {
        this.defaultAutoPayAgreement = defaultAutoPayAgreement;
    }

    public long getAvailableCouponCount() {
        return availableCouponCount;
    }

    public void setAvailableCouponCount(long availableCouponCount) {
        this.availableCouponCount = availableCouponCount;
    }
}
