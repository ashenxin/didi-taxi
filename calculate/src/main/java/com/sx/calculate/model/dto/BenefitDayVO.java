package com.sx.calculate.model.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@Accessors(chain = true)
public class BenefitDayVO {
    private Integer dayOfMonth;
    private String date;
    private Boolean signed;
    private Integer rewardPoints;
    private String rewardRuleCode;
}
