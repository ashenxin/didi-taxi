package com.sx.passengerapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "coupon.claim-identity")
public class CouponClaimIdentityProperties {

    private String phoneHashSecret = "";

    public String getPhoneHashSecret() {
        return phoneHashSecret;
    }

    public void setPhoneHashSecret(String phoneHashSecret) {
        this.phoneHashSecret = phoneHashSecret;
    }
}
