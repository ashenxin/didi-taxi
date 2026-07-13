package com.sx.passengerapi.model.wallet;

import java.util.List;

public class CouponClaimRequest {
    private List<Long> templateIds;
    private String claimIdentityType;
    private String claimIdentityHash;

    public List<Long> getTemplateIds() {
        return templateIds;
    }

    public void setTemplateIds(List<Long> templateIds) {
        this.templateIds = templateIds;
    }

    public String getClaimIdentityType() {
        return claimIdentityType;
    }

    public void setClaimIdentityType(String claimIdentityType) {
        this.claimIdentityType = claimIdentityType;
    }

    public String getClaimIdentityHash() {
        return claimIdentityHash;
    }

    public void setClaimIdentityHash(String claimIdentityHash) {
        this.claimIdentityHash = claimIdentityHash;
    }
}
