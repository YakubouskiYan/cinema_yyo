package com.cinemaabyss.events.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public class PaymentRequest {
    @JsonProperty("payment_id")
    private int paymentId;
    @JsonProperty("user_id")
    private int userId;
    private double amount;
    private String status;
    private String timestamp;
    @JsonProperty("method_type")
    private String methodType;

    public int getPaymentId() { return paymentId; }
    public void setPaymentId(int paymentId) { this.paymentId = paymentId; }
    public int getUserId() { return userId; }
    public void setUserId(int userId) { this.userId = userId; }
    public double getAmount() { return amount; }
    public void setAmount(double amount) { this.amount = amount; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    public String getMethodType() { return methodType; }
    public void setMethodType(String methodType) { this.methodType = methodType; }
}
