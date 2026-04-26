package com.sx.passengerapi.model.order;

import com.sx.passengerapi.model.calculate.EstimateFareResult;
import com.sx.passengerapi.model.map.RouteResponse;

/**
 * 两段式下单返回：只保证订单已创建，派单异步推进。
 * {@code POST /app/api/v1/orders/create}
 */
public class CreateOrderResultV1 {

    private String orderNo;
    private OrderStatus status;
    private AssignedDriver assignedDriver;
    private RouteResponse route;
    private EstimateFareResult estimate;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public void setStatus(OrderStatus status) {
        this.status = status;
    }

    public AssignedDriver getAssignedDriver() {
        return assignedDriver;
    }

    public void setAssignedDriver(AssignedDriver assignedDriver) {
        this.assignedDriver = assignedDriver;
    }

    public RouteResponse getRoute() {
        return route;
    }

    public void setRoute(RouteResponse route) {
        this.route = route;
    }

    public EstimateFareResult getEstimate() {
        return estimate;
    }

    public void setEstimate(EstimateFareResult estimate) {
        this.estimate = estimate;
    }
}

