package com.sx.order.model.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class PaymentAttemptResult {
    private String paymentNo;
    private String orderNo;
    private Long passengerId;
    private String status;
    private String channel;
    private BigDecimal amount;
    private String channelTradeNo;
    private LocalDateTime occurredAt;
    private String checkoutUrl;

    public String getPaymentNo() { return paymentNo; }
    public void setPaymentNo(String paymentNo) { this.paymentNo = paymentNo; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public Long getPassengerId() { return passengerId; }
    public void setPassengerId(Long passengerId) { this.passengerId = passengerId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getChannelTradeNo() { return channelTradeNo; }
    public void setChannelTradeNo(String channelTradeNo) { this.channelTradeNo = channelTradeNo; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; }
    public String getCheckoutUrl() { return checkoutUrl; }
    public void setCheckoutUrl(String checkoutUrl) { this.checkoutUrl = checkoutUrl; }
}
