package com.sx.passengerapi.model.ordercore;

public class BlockingOrderResult {
    private String blockingOrderNo;
    private String settlementStatus;
    private String action;

    public String getBlockingOrderNo() { return blockingOrderNo; }
    public void setBlockingOrderNo(String blockingOrderNo) { this.blockingOrderNo = blockingOrderNo; }
    public String getSettlementStatus() { return settlementStatus; }
    public void setSettlementStatus(String settlementStatus) { this.settlementStatus = settlementStatus; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
}
