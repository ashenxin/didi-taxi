package com.sx.map.model.dto;

public class AmapPoiCandidate {

    private String poiId;
    private String name;
    private String type;
    private String typeCode;
    private String province;
    private String city;
    private String district;
    private String address;
    private Point centerPoint;
    private Point entrancePoint;
    private Point exitPoint;

    public String getPoiId() {
        return poiId;
    }

    public void setPoiId(String poiId) {
        this.poiId = poiId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTypeCode() {
        return typeCode;
    }

    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    public String getProvince() {
        return province;
    }

    public void setProvince(String province) {
        this.province = province;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getDistrict() {
        return district;
    }

    public void setDistrict(String district) {
        this.district = district;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public Point getCenterPoint() {
        return centerPoint;
    }

    public void setCenterPoint(Point centerPoint) {
        this.centerPoint = centerPoint;
    }

    public Point getEntrancePoint() {
        return entrancePoint;
    }

    public void setEntrancePoint(Point entrancePoint) {
        this.entrancePoint = entrancePoint;
    }

    public Point getExitPoint() {
        return exitPoint;
    }

    public void setExitPoint(Point exitPoint) {
        this.exitPoint = exitPoint;
    }
}
