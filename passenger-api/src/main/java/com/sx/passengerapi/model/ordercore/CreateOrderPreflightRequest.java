package com.sx.passengerapi.model.ordercore;

public class CreateOrderPreflightRequest {
    private Long passengerId;
    private String provinceCode;
    private String cityCode;
    private String productCode;
    private Place origin;
    private Place dest;

    public Long getPassengerId() { return passengerId; }
    public void setPassengerId(Long passengerId) { this.passengerId = passengerId; }
    public String getProvinceCode() { return provinceCode; }
    public void setProvinceCode(String provinceCode) { this.provinceCode = provinceCode; }
    public String getCityCode() { return cityCode; }
    public void setCityCode(String cityCode) { this.cityCode = cityCode; }
    public String getProductCode() { return productCode; }
    public void setProductCode(String productCode) { this.productCode = productCode; }
    public Place getOrigin() { return origin; }
    public void setOrigin(Place origin) { this.origin = origin; }
    public Place getDest() { return dest; }
    public void setDest(Place dest) { this.dest = dest; }
}
