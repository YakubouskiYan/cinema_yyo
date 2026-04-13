package com.cinemaabyss.events.model;

import com.cinemaabyss.events.model.request.MovieRequest;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public class MovieEvent {
    @JsonProperty("event_id")
    private final String eventId;
    @JsonProperty("published_at")
    private final String publishedAt;
    @JsonProperty("movie_id")
    private final int movieId;
    private final String title;
    private final String action;
    @JsonProperty("user_id")
    private final int userId;

    private MovieEvent(String eventId, String publishedAt, int movieId, String title, String action, int userId) {
        this.eventId = eventId;
        this.publishedAt = publishedAt;
        this.movieId = movieId;
        this.title = title;
        this.action = action;
        this.userId = userId;
    }

    public static MovieEvent from(MovieRequest req) {
        return new MovieEvent(
                UUID.randomUUID().toString(),
                Instant.now().toString(),
                req.getMovieId(),
                req.getTitle(),
                req.getAction(),
                req.getUserId()
        );
    }

    public String getEventId() { return eventId; }
    public String getPublishedAt() { return publishedAt; }
    public int getMovieId() { return movieId; }
    public String getTitle() { return title; }
    public String getAction() { return action; }
    public int getUserId() { return userId; }
}
