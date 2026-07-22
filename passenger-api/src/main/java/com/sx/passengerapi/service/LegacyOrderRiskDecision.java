package com.sx.passengerapi.service;

public enum LegacyOrderRiskDecision {
    PASS,
    ACTIVE_ORDER,
    UNSETTLED_ORDER;

    public boolean blocked() {
        return this != PASS;
    }
}
