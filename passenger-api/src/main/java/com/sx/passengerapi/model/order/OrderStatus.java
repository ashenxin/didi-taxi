package com.sx.passengerapi.model.order;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * 订单状态（与 order-service.trip_order.status 对齐）。
 *
 * <p>通过 {@link JsonFormat.Shape#OBJECT} 让前端拿到 code/en/zh，便于联调与展示。</p>
 */
@JsonFormat(shape = JsonFormat.Shape.OBJECT)
public enum OrderStatus {
    CREATED(0, "CREATED", "已创建"),
    ASSIGNED(1, "ASSIGNED", "已派单"),
    ACCEPTED(2, "ACCEPTED", "已接单"),
    ARRIVED(3, "ARRIVED", "司机已到达"),
    STARTED(4, "STARTED", "行程中"),
    FINISHED(5, "FINISHED", "已完成"),
    CANCELLED(6, "CANCELLED", "已取消"),
    /** 待司机在派单确认窗口内确认 */
    PENDING_DRIVER_CONFIRM(7, "PENDING_DRIVER_CONFIRM", "待司机确认");

    private final int code;
    private final String en;
    private final String zh;

    OrderStatus(int code, String en, String zh) {
        this.code = code;
        this.en = en;
        this.zh = zh;
    }

    public int getCode() {
        return code;
    }

    public String getEn() {
        return en;
    }

    public String getZh() {
        return zh;
    }

    public static OrderStatus fromCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (OrderStatus s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        return null;
    }
}

