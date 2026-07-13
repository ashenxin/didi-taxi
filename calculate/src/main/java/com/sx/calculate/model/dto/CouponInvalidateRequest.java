package com.sx.calculate.model.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CouponInvalidateRequest {
    private Long passengerId;
    private String reason;
}
