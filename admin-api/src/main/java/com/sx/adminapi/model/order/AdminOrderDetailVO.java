package com.sx.adminapi.model.order;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AdminOrderDetailVO {
    private AdminOrderVO order;
    private List<AdminOrderEventVO> events = new ArrayList<>();
}
