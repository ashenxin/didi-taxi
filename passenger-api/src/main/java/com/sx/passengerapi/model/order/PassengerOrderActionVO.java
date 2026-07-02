package com.sx.passengerapi.model.order;

/**
 * 乘客端订单卡片上的按钮占位信息。
 * 本期只负责展示，不承载真实业务动作。
 */
public class PassengerOrderActionVO {

    private String code;
    private String label;
    private Boolean disabled;
    private Boolean implemented;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public Boolean getDisabled() {
        return disabled;
    }

    public void setDisabled(Boolean disabled) {
        this.disabled = disabled;
    }

    public Boolean getImplemented() {
        return implemented;
    }

    public void setImplemented(Boolean implemented) {
        this.implemented = implemented;
    }
}
