package com.sx.driverapi.model.auth;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DriverProfileVO {
    private Long id;
    private String phone;
    private Integer auditStatus;
}

