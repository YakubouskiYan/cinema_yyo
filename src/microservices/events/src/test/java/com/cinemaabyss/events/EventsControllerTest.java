package com.cinemaabyss.events;

import com.cinemaabyss.events.kafka.EventProducer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web-layer tests for EventsController.
 *
 * EventProducer (and therefore Kafka) is mocked — no broker required.
 * Tests verify HTTP contract: status codes, response shape, and that
 * the controller delegates to the producer with the correct topic.
 */
@WebMvcTest
class EventsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    EventProducer producer;

    // ── Health ────────────────────────────────────────────────────────────────

    @Test
    void health_returns200WithStatusTrue() throws Exception {
        mockMvc.perform(get("/api/events/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(true));
    }

    // ── Movie events ──────────────────────────────────────────────────────────

    @Test
    void movieEvent_returns201_withEventFields() throws Exception {
        String body = """
                {"movie_id": 1, "title": "Inception", "action": "viewed", "user_id": 42}
                """;

        mockMvc.perform(post("/api/events/movie")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.event.movie_id").value(1))
                .andExpect(jsonPath("$.event.title").value("Inception"))
                .andExpect(jsonPath("$.event.action").value("viewed"))
                .andExpect(jsonPath("$.event.user_id").value(42))
                // Factory must generate these fields automatically
                .andExpect(jsonPath("$.event.event_id").isString())
                .andExpect(jsonPath("$.event.published_at").isString());
    }

    @Test
    void movieEvent_publishesToCorrectTopic() throws Exception {
        String body = """
                {"movie_id": 7, "title": "Dune", "action": "rated", "user_id": 3}
                """;

        mockMvc.perform(post("/api/events/movie")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // Verify producer was called with the movie-events topic
        verify(producer).publish(eq("movie-events"), any(), any());
    }

    // ── User events ───────────────────────────────────────────────────────────

    @Test
    void userEvent_returns201_withEventFields() throws Exception {
        String body = """
                {"user_id": 10, "action": "registered", "username": "alice"}
                """;

        mockMvc.perform(post("/api/events/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.event.user_id").value(10))
                .andExpect(jsonPath("$.event.event_id").isString())
                .andExpect(jsonPath("$.event.published_at").isString());
    }

    @Test
    void userEvent_publishesToCorrectTopic() throws Exception {
        String body = """
                {"user_id": 10, "action": "registered", "username": "alice"}
                """;

        mockMvc.perform(post("/api/events/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        verify(producer).publish(eq("user-events"), any(), any());
    }

    // ── Payment events ────────────────────────────────────────────────────────

    @Test
    void paymentEvent_returns201_withEventFields() throws Exception {
        String body = """
                {"payment_id": 99, "user_id": 5, "amount": 49.99, "status": "completed"}
                """;

        mockMvc.perform(post("/api/events/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.event.payment_id").value(99))
                .andExpect(jsonPath("$.event.user_id").value(5))
                .andExpect(jsonPath("$.event.event_id").isString())
                .andExpect(jsonPath("$.event.published_at").isString());
    }

    @Test
    void paymentEvent_publishesToCorrectTopic() throws Exception {
        String body = """
                {"payment_id": 99, "user_id": 5, "amount": 49.99, "status": "completed"}
                """;

        mockMvc.perform(post("/api/events/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        verify(producer).publish(eq("payment-events"), any(), any());
    }
}
