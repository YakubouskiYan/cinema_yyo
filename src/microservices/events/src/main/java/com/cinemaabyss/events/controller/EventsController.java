package com.cinemaabyss.events.controller;

import com.cinemaabyss.events.kafka.EventProducer;
import com.cinemaabyss.events.model.MovieEvent;
import com.cinemaabyss.events.model.PaymentEvent;
import com.cinemaabyss.events.model.UserEvent;
import com.cinemaabyss.events.model.EventResponse;
import com.cinemaabyss.events.model.request.MovieRequest;
import com.cinemaabyss.events.model.request.PaymentRequest;
import com.cinemaabyss.events.model.request.UserRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/events")
public class EventsController {

    private final EventProducer producer;

    public EventsController(EventProducer producer) {
        this.producer = producer;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Boolean>> health() {
        return ResponseEntity.ok(Map.of("status", true));
    }

    @PostMapping("/movie")
    public ResponseEntity<EventResponse> movieEvent(@RequestBody MovieRequest request) throws Exception {
        MovieEvent event = MovieEvent.from(request);
        producer.publish("movie-events", "movie-" + event.getMovieId(), event);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new EventResponse("success", 0, 0, event));
    }

    @PostMapping("/user")
    public ResponseEntity<EventResponse> userEvent(@RequestBody UserRequest request) throws Exception {
        UserEvent event = UserEvent.from(request);
        producer.publish("user-events", "user-" + event.getUserId(), event);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new EventResponse("success", 0, 0, event));
    }

    @PostMapping("/payment")
    public ResponseEntity<EventResponse> paymentEvent(@RequestBody PaymentRequest request) throws Exception {
        PaymentEvent event = PaymentEvent.from(request);
        producer.publish("payment-events", "payment-" + event.getPaymentId(), event);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new EventResponse("success", 0, 0, event));
    }
}
