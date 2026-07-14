package com.sx.calculate.model.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@Accessors(chain = true)
public class BenefitClearPointsRequest {
    private Long customerId;
    private String cancelRequestId;
}
