package com.sx.driverapi.model.teamchange;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class TeamChangeRequestVO {
    private Long id;
    private String status;
    private Date requestedAt;
    private String requestReason;

    private Long fromCompanyId;
    private String fromTeamName;
    private Long toCompanyId;
    private String toTeamName;

    private Date reviewedAt;
    private String reviewedBy;
    private String reviewReason;
}

