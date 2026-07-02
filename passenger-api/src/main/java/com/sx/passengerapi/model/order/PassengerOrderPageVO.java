package com.sx.passengerapi.model.order;

import java.util.List;

/**
 * 乘客端“我的订单”分页结果。
 */
public class PassengerOrderPageVO {

    private List<PassengerOrderListItemVO> list;
    private Integer total;
    private Integer pageNo;
    private Integer pageSize;
    private PassengerOrderListType type;

    public List<PassengerOrderListItemVO> getList() {
        return list;
    }

    public void setList(List<PassengerOrderListItemVO> list) {
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

    public PassengerOrderListType getType() {
        return type;
    }

    public void setType(PassengerOrderListType type) {
        this.type = type;
    }
}
