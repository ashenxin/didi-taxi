package com.sx.driverapi.model.teamchange;

import lombok.Getter;
import lombok.Setter;

/**
 * 司机换队页「当前归属（只读）」展示所需信息。
 */
@Getter
@Setter
public class DriverBelongingVO {
    private String cityCode;
    private String cityName;

    private Long fromCompanyId;
    private String fromCompanyName;
    private Long fromTeamId;
    private String fromTeamName;

    private Boolean canAcceptOrder;
    private Integer monitorStatus;
}

