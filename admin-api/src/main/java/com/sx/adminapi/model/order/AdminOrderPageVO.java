package com.sx.adminapi.model.order;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AdminOrderPageVO {
    private List<AdminOrderVO> list = new ArrayList<>();
    private Long total = 0L;
    private Integer pageNo;
    private Integer pageSize;
}
