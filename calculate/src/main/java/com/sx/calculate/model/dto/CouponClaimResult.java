package com.sx.calculate.model.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CouponClaimResult {
    private int claimedCount;
    private int skippedCount;
}
