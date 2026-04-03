package com.sx.adminapi.model.capacity;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class AdminPageVO<T> {
    private List<T> list;
    private Long total;
    private Integer pageNo;
    private Integer pageSize;
}

