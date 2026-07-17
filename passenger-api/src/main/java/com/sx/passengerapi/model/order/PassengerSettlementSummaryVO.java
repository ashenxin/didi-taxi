package com.sx.passengerapi.model.order;

import java.util.List;

public class PassengerSettlementSummaryVO {
    private String settlementStatus;
    private String message;
    private List<PassengerOrderActionVO> actions;

    public String getSettlementStatus() { return settlementStatus; }
    public void setSettlementStatus(String settlementStatus) { this.settlementStatus = settlementStatus; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public List<PassengerOrderActionVO> getActions() { return actions; }
    public void setActions(List<PassengerOrderActionVO> actions) { this.actions = actions; }
}
