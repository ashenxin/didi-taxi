package com.sx.passengerapi.client.dto;

public class AppSmsSendRequest {

    private String phone;

    public AppSmsSendRequest() {
    }

    public AppSmsSendRequest(String phone) {
        this.phone = phone;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }
}
