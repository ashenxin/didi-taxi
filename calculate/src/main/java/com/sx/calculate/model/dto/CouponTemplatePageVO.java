package com.sx.calculate.model.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class CouponTemplatePageVO {
    private long total;
    private int pageNo;
    private int pageSize;
    private List<CouponTemplateVO> list;
}
