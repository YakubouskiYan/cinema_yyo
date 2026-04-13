package com.cinemaabyss.events.model;

public class EventResponse {
    private String status;
    private int partition;
    private long offset;
    private Object event;

    public EventResponse(String status, int partition, long offset, Object event) {
        this.status = status;
        this.partition = partition;
        this.offset = offset;
        this.event = event;
    }

    public String getStatus() { return status; }
    public int getPartition() { return partition; }
    public long getOffset() { return offset; }
    public Object getEvent() { return event; }
}
