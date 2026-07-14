package com.sx.passengerapi.model.benefit;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BenefitClearPointsRequest {
    private Long customerId;
    private String cancelRequestId;

    public BenefitClearPointsRequest() {
    }

    public BenefitClearPointsRequest(Long customerId, String cancelRequestId) {
        this.customerId = customerId;
        this.cancelRequestId = cancelRequestId;
    }
}
