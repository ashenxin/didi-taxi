package com.sx.calculate.model.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class CouponClaimRequest {
    private List<Long> templateIds;
    private String claimIdentityType;
    private String claimIdentityHash;
}
