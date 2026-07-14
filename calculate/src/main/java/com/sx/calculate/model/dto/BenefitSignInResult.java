package com.sx.calculate.model.dto;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@Accessors(chain = true)
public class BenefitSignInResult {
    private Boolean newSigned;
    private String message;
    private String businessDate;
    private String yearMonth;
    private Boolean signedToday;
    private Integer continuousDays;
    private Integer rewardPoints;
    private String rewardRuleCode;
    private Integer availablePoints;
    private Boolean signEnabled;
    private String disabledReason;
}
