package com.cinemaabyss.events.model.request;

import com.fasterxml.jackson.annotation.JsonProperty;

public class UserRequest {
    @JsonProperty("user_id")
    private int userId;
    private String username;
    private String action;
    private String timestamp;

    public int getUserId() { return userId; }
    public void setUserId(int userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
}
