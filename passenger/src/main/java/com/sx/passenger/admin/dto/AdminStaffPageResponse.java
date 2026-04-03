package com.sx.passenger.admin.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class AdminStaffPageResponse {

    private List<AdminStaffUserVO> list = new ArrayList<>();

    private Long total;

    private Integer pageNo;

    private Integer pageSize;
}
