package com.sx.passengerapi.model.order;

import com.sx.passengerapi.common.exception.BizErrorException;
import com.sx.passengerapi.model.ordercore.TripOrderRow;

import java.util.Locale;
import java.util.Set;

/**
 * 乘客端“我的订单”页面筛选类型。
 */
public enum PassengerOrderListType {
    ALL("ALL", "全部", null),
    TO_DEPART("TO_DEPART", "待出发", Set.of(0, 1, 2, 3, 7)),
    REFUND_CANCEL("REFUND_CANCEL", "退款与取消", Set.of(6));

    private final String code;
    private final String label;
    private final Set<Integer> statuses;

    PassengerOrderListType(String code, String label, Set<Integer> statuses) {
        this.code = code;
        this.label = label;
        this.statuses = statuses;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public static PassengerOrderListType fromQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        String normalized = raw.trim();
        if ("全部".equals(normalized)) {
            return ALL;
        }
        if ("待出发".equals(normalized)) {
            return TO_DEPART;
        }
        if ("退款与取消".equals(normalized) || "退款取消".equals(normalized)) {
            return REFUND_CANCEL;
        }
        normalized = normalized.toUpperCase(Locale.ROOT).replace('-', '_');
        for (PassengerOrderListType type : values()) {
            if (type.code.equals(normalized)) {
                return type;
            }
        }
        throw new BizErrorException(400, "订单类型不正确");
    }

    public boolean matches(TripOrderRow row) {
        if (this == ALL) {
            return true;
        }
        Integer status = row == null ? null : row.getStatus();
        return status != null && statuses.contains(status);
    }
}
