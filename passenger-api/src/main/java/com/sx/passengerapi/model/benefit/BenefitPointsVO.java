package com.sx.passengerapi.model.benefit;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BenefitPointsVO {
    private Integer availablePoints;
    private Integer totalEarnedPoints;
    private Integer totalUsedPoints;
    private Integer totalClearedPoints;
    private String accountStatus;
    private String refreshedAt;
}
