package com.sx.passenger.app.dto;

import java.io.Serializable;

/** 登录成功后返回给 BFF 的乘客摘要（用于签发 JWT）。 */
public class AppAuthCustomerBrief implements Serializable {

    private Long id;
    private String phone;
    private String nickname;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }
}
