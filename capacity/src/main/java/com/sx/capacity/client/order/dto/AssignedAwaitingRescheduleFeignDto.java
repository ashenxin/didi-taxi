package com.sx.capacity.client.order.dto;

import java.math.BigDecimal;

public class AssignedAwaitingRescheduleFeignDto {

    private String orderNo;
    private String cityCode;
    private String productCode;
    private BigDecimal originLat;
    private BigDecimal originLng;
    private Long driverId;
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
