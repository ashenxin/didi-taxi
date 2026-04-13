package com.sx.passengerapi.model.order;

import com.sx.passengerapi.model.calculate.EstimateFareResult;
import com.sx.passengerapi.model.map.RouteResponse;

/**
 * 下单（createAndAssign）接口返回数据。
 *
 * MVP 阶段该对象会逐步扩展：当前先回传路线预估与计费预估，
 * 后续再补 {@code orderNo/status/assignedDriver} 等字段。
 */
public class CreateAndAssignOrderResult {

    /**
     * 订单号（由 order-service 创建后生成）。
     */
    private String orderNo;

    /**
     * 订单状态（MVP：`CREATED` / `ASSIGNED`）。
     *
     * 与 order-service.trip_order.status 对齐，返回包含 code/en/zh 的枚举对象，便于前端展示与联调。
     */
    private OrderStatus status;

    /**
     * 已指派司机信息（派单模式；若暂无可用司机则为空）。
     */
    private AssignedDriver assignedDriver;

    /**
     * 路线规划结果（来自 map-service 的 route）。
     *
     * 包含预估里程/时长等，用于后续计费预估与展示。
     */
    private RouteResponse route;

    /**
     * 预估费用结果（来自 calculate-service 的 estimate）。
     *
     * 包含命中的计价规则与预估金额。
     */
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

