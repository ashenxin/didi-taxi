package com.sx.passengerapi.model.benefit;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BenefitDayVO {
    private Integer dayOfMonth;
    private String date;
    private Boolean signed;
    private Integer rewardPoints;
    private String rewardRuleCode;
}
