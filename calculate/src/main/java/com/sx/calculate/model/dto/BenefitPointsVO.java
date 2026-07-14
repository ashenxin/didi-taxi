package com.sx.calculate.model.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@Accessors(chain = true)
public class BenefitPointsVO {
    private Integer availablePoints;
    private Integer totalEarnedPoints;
    private Integer totalUsedPoints;
    private Integer totalClearedPoints;
    private String accountStatus;
    private String refreshedAt;
}
