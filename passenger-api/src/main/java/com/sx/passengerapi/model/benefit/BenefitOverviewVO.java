package com.sx.passengerapi.model.benefit;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class BenefitOverviewVO {
    private String businessDate;
    private String yearMonth;
    private Integer displayDays;
    private Boolean signEnabled;
    private String disabledReason;
    private Boolean signedToday;
    private Integer continuousDays;
    private Integer availablePoints;
    private Integer todayRewardPoints;
    private String todayRewardRuleCode;
    private List<BenefitDayVO> days = new ArrayList<>();
}
