package com.cinemaabyss.events.model;

import com.cinemaabyss.events.model.request.UserRequest;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public class UserEvent {
    @JsonProperty("event_id")
    private final String eventId;
    @JsonProperty("published_at")
    private final String publishedAt;
    @JsonProperty("user_id")
    private final int userId;
    private final String username;
    private final String action;
    private final String timestamp;

    private UserEvent(String eventId, String publishedAt, int userId, String username, String action, String timestamp) {
        this.eventId = eventId;
        this.publishedAt = publishedAt;
        this.userId = userId;
        this.username = username;
        this.action = action;
        this.timestamp = timestamp;
    }

    public static UserEvent from(UserRequest req) {
        return new UserEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                req.getUserId(),
                req.getUsername(),
                req.getAction(),
                req.getTimestamp()
        );
    }

    public String getEventId() { return eventId; }
    public String getPublishedAt() { return publishedAt; }
    public int getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getAction() { return action; }
    public String getTimestamp() { return timestamp; }
}
