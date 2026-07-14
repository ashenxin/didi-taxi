package com.sx.passengerapi.model.benefit;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
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
