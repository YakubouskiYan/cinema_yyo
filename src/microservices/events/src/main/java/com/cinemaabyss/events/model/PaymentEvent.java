package com.cinemaabyss.events.model;

import com.cinemaabyss.events.model.request.PaymentRequest;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public class PaymentEvent {
    @JsonProperty("event_id")
    private final String eventId;
    @JsonProperty("published_at")
    private final String publishedAt;
    @JsonProperty("payment_id")
    private final int paymentId;
    @JsonProperty("user_id")
    private final int userId;
    private final double amount;
    private final String status;
    private final String timestamp;
    @JsonProperty("method_type")
    private final String methodType;

    private PaymentEvent(String eventId, String publishedAt, int paymentId, int userId,
                         double amount, String status, String timestamp, String methodType) {
        this.eventId = eventId;
        this.publishedAt = publishedAt;
        this.paymentId = paymentId;
        this.userId = userId;
        this.amount = amount;
        this.status = status;
        this.timestamp = timestamp;
        this.methodType = methodType;
    }

    public static PaymentEvent from(PaymentRequest req) {
        return new PaymentEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                req.getPaymentId(),
                req.getUserId(),
                req.getAmount(),
                req.getStatus(),
                req.getTimestamp(),
                req.getMethodType()
        );
    }

    public String getEventId() { return eventId; }
    public String getPublishedAt() { return publishedAt; }
    public int getPaymentId() { return paymentId; }
    public int getUserId() { return userId; }
    public double getAmount() { return amount; }
    public String getStatus() { return status; }
    public String getTimestamp() { return timestamp; }
    public String getMethodType() { return methodType; }
}
