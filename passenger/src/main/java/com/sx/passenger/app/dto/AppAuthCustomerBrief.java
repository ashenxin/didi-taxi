package com.sx.passenger.app.dto;

import com.sx.passenger.model.Customer;

import java.io.Serializable;

/** 登录成功后返回给 BFF 的乘客摘要（用于签发 JWT）。 */
public class AppAuthCustomerBrief implements Serializable {

    private Long id;
    private String phone;
    private String nickname;
    private Long authEpoch;
    private String scope;
    private String operationNo;

    public static AppAuthCustomerBrief from(Customer customer, String scope) {
        AppAuthCustomerBrief brief = new AppAuthCustomerBrief();
        brief.setId(customer.getId());
        brief.setPhone(customer.getPhone());
        brief.setNickname(customer.getNickname());
        brief.setAuthEpoch(customer.getAuthEpoch());
        brief.setScope(scope);
        if ("LIFECYCLE_RESTRICTED".equals(scope)) {
            brief.setOperationNo(customer.getCurrentLifecycleOperationNo());
        }
        return brief;
    }

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

    public Long getAuthEpoch() {
        return authEpoch;
    }

    public void setAuthEpoch(Long authEpoch) {
        this.authEpoch = authEpoch;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getOperationNo() {
        return operationNo;
    }

    public void setOperationNo(String operationNo) {
        this.operationNo = operationNo;
    }
}
