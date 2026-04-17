package com.sx.order.model.dto;

import java.math.BigDecimal;

/**
 * 内部：确认窗口已结束（回到 {@code ASSIGNED}）且曾发起过 offer，等待调度改派或下一轮 offer。
 */
public class AssignedAwaitingRescheduleDto {

    private String orderNo;
    private String cityCode;
    private String productCode;
    private BigDecimal originLat;
    private BigDecimal originLng;
    /** 当前指派司机（上一轮 offer 对象） */
    private Long driverId;
    /** 最近一次已完成的 offer 轮次（与 trip_order.offer_round 一致） */
    private Integer offerRound;

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public String getCityCode() {
        return cityCode;
    }

    public void setCityCode(String cityCode) {
        this.cityCode = cityCode;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public BigDecimal getOriginLat() {
        return originLat;
    }

    public void setOriginLat(BigDecimal originLat) {
        this.originLat = originLat;
    }

    public BigDecimal getOriginLng() {
        return originLng;
    }

    public void setOriginLng(BigDecimal originLng) {
        this.originLng = originLng;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public Integer getOfferRound() {
        return offerRound;
    }

    public void setOfferRound(Integer offerRound) {
        this.offerRound = offerRound;
    }
}
