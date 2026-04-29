package com.sx.passengerapi.model.ordercore;

import java.util.List;

/**
 * 与 order-service {@code GET /api/v1/orders} 分页 data 结构对齐。
 */
public class OrderPageData {
    private List<TripOrderRow> list;
    private Integer total;
    private Integer pageNo;
    private Integer pageSize;

    public List<TripOrderRow> getList() {
        return list;
    }

    public void setList(List<TripOrderRow> list) {
        this.list = list;
    }

    public Integer getTotal() {
        return total;
    }

    public void setTotal(Integer total) {
        this.total = total;
    }

    public Integer getPageNo() {
        return pageNo;
    }

    public void setPageNo(Integer pageNo) {
        this.pageNo = pageNo;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }
}
