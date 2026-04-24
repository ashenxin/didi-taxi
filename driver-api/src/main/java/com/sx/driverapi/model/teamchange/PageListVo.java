package com.sx.driverapi.model.teamchange;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PageListVo<T> {
    private List<T> list;
    private Long total;
    private Integer pageNo;
    private Integer pageSize;
}

