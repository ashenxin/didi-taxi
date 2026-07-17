package com.sx.passengerapi.model.settlement;

public record PaymentInvokeResult(String paymentNo, String status, InvokePayload invokePayload) {
    public record InvokePayload(String type, String checkoutUrl) {
    }
}
