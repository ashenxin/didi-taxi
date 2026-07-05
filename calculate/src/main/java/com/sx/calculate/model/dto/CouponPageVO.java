package com.sx.calculate.model.dto;

import java.util.List;

public class CouponPageVO {
    private long total;
    private int pageNo;
    private int pageSize;
    private List<CouponVO> list;

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public int getPageNo() {
        return pageNo;
    }

    public void setPageNo(int pageNo) {
        this.pageNo = pageNo;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public List<CouponVO> getList() {
        return list;
    }

    public void setList(List<CouponVO> list) {
        this.list = list;
    }
}
