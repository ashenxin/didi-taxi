package com.sx.passengerapi.model.ordercore;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 与 order-service {@code TripOrder} JSON 对齐，供 Feign 反序列化。
 */
public class TripOrderRow {
    private Long id;
    private String orderNo;
    private Long passengerId;
    private Long driverId;
    private Long carId;
    private Long companyId;
    private String productCode;
    private String provinceCode;
    private String cityCode;
    private String originAddress;
    private BigDecimal originLat;
    private BigDecimal originLng;
    private String destAddress;
    private BigDecimal destLat;
    private BigDecimal destLng;
    private Integer status;
    private BigDecimal estimatedAmount;
    private BigDecimal finalAmount;
    private Long fareRuleId;
    private String fareRuleSnapshot;
    private Integer cancelBy;
    private String cancelReason;
    private LocalDateTime createdAt;
    private LocalDateTime assignedAt;
    private LocalDateTime offerExpiresAt;
    private Integer offerRound;
    private LocalDateTime lastOfferAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime arrivedAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime updatedAt;
    private Integer isDeleted;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public Long getPassengerId() {
        return passengerId;
    }

    public void setPassengerId(Long passengerId) {
        this.passengerId = passengerId;
    }

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public Long getCarId() {
        return carId;
    }

    public void setCarId(Long carId) {
        this.carId = carId;
    }

    public Long getCompanyId() {
        return companyId;
    }

    public void setCompanyId(Long companyId) {
        this.companyId = companyId;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getProvinceCode() {
        return provinceCode;
    }

    public void setProvinceCode(String provinceCode) {
        this.provinceCode = provinceCode;
    }

    public String getCityCode() {
        return cityCode;
    }

    public void setCityCode(String cityCode) {
        this.cityCode = cityCode;
    }

    public String getOriginAddress() {
        return originAddress;
    }

    public void setOriginAddress(String originAddress) {
        this.originAddress = originAddress;
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

    public String getDestAddress() {
        return destAddress;
    }

    public void setDestAddress(String destAddress) {
        this.destAddress = destAddress;
    }

    public BigDecimal getDestLat() {
        return destLat;
    }

    public void setDestLat(BigDecimal destLat) {
        this.destLat = destLat;
    }

    public BigDecimal getDestLng() {
        return destLng;
    }

    public void setDestLng(BigDecimal destLng) {
        this.destLng = destLng;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public BigDecimal getEstimatedAmount() {
        return estimatedAmount;
    }

    public void setEstimatedAmount(BigDecimal estimatedAmount) {
        this.estimatedAmount = estimatedAmount;
    }

    public BigDecimal getFinalAmount() {
        return finalAmount;
    }

    public void setFinalAmount(BigDecimal finalAmount) {
        this.finalAmount = finalAmount;
    }

    public Long getFareRuleId() {
        return fareRuleId;
    }

    public void setFareRuleId(Long fareRuleId) {
        this.fareRuleId = fareRuleId;
    }

    public String getFareRuleSnapshot() {
        return fareRuleSnapshot;
    }

    public void setFareRuleSnapshot(String fareRuleSnapshot) {
        this.fareRuleSnapshot = fareRuleSnapshot;
    }

    public Integer getCancelBy() {
        return cancelBy;
    }

    public void setCancelBy(Integer cancelBy) {
        this.cancelBy = cancelBy;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getAssignedAt() {
        return assignedAt;
    }

    public void setAssignedAt(LocalDateTime assignedAt) {
        this.assignedAt = assignedAt;
    }

    public LocalDateTime getOfferExpiresAt() {
        return offerExpiresAt;
    }

    public void setOfferExpiresAt(LocalDateTime offerExpiresAt) {
        this.offerExpiresAt = offerExpiresAt;
    }

    public Integer getOfferRound() {
        return offerRound;
    }

    public void setOfferRound(Integer offerRound) {
        this.offerRound = offerRound;
    }

    public LocalDateTime getLastOfferAt() {
        return lastOfferAt;
    }

    public void setLastOfferAt(LocalDateTime lastOfferAt) {
        this.lastOfferAt = lastOfferAt;
    }

    public LocalDateTime getAcceptedAt() {
        return acceptedAt;
    }

    public void setAcceptedAt(LocalDateTime acceptedAt) {
        this.acceptedAt = acceptedAt;
    }

    public LocalDateTime getArrivedAt() {
        return arrivedAt;
    }

    public void setArrivedAt(LocalDateTime arrivedAt) {
        this.arrivedAt = arrivedAt;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public LocalDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(LocalDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Integer getIsDeleted() {
        return isDeleted;
    }

    public void setIsDeleted(Integer isDeleted) {
        this.isDeleted = isDeleted;
    }
}
