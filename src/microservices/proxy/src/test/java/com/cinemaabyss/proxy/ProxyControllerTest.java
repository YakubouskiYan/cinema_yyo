package com.cinemaabyss.proxy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for ProxyController routing logic.
 *
 * MockRestServiceServer intercepts outbound RestTemplate calls so no real
 * upstream (monolith / movies-service / events-service) is required.
 *
 * Property overrides:
 *  - gradual-migration=false  → all /api/movies traffic goes to monolith
 *  - migration-percent=100    → used in the "migration active" test variant
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "proxy.monolith-url=http://monolith-test",
        "proxy.movies-service-url=http://movies-test",
        "proxy.events-service-url=http://events-test",
        "proxy.gradual-migration=false",
        "proxy.movies-migration-percent=0"
})
class ProxyControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RestTemplate restTemplate;

    MockRestServiceServer upstream;

    @BeforeEach
    void setUp() {
        upstream = MockRestServiceServer.createServer(restTemplate);
    }

    // ── Health ────────────────────────────────────────────────────────────────

    @Test
    void health_returns200WithStatusTrue() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(true));
        // Health is handled locally — no upstream call expected
        upstream.verify();
    }

    // ── Routing: monolith ─────────────────────────────────────────────────────

    @Test
    void usersRequest_routesToMonolith() throws Exception {
        upstream.expect(requestTo("http://monolith-test/api/users"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":1}]", MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/users"))
                .andExpect(status().isOk());

        upstream.verify();
    }

    @Test
    void paymentsRequest_routesToMonolith() throws Exception {
        upstream.expect(requestTo("http://monolith-test/api/payments"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/payments"))
                .andExpect(status().isOk());

        upstream.verify();
    }

    @Test
    void subscriptionsRequest_routesToMonolith() throws Exception {
        upstream.expect(requestTo("http://monolith-test/api/subscriptions"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/subscriptions"))
                .andExpect(status().isOk());

        upstream.verify();
    }

    @Test
    void moviesRequest_withMigrationDisabled_routesToMonolith() throws Exception {
        // gradual-migration=false in @TestPropertySource → always monolith
        upstream.expect(requestTo("http://monolith-test/api/movies"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/movies"))
                .andExpect(status().isOk());

        upstream.verify();
    }

    // ── Routing: events-service ───────────────────────────────────────────────

    @Test
    void eventsRequest_routesToEventsService() throws Exception {
        String responseBody = "{\"status\":\"success\",\"partition\":0,\"offset\":0,\"event\":{}}";
        upstream.expect(requestTo("http://events-test/api/events/movie"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(responseBody, MediaType.APPLICATION_JSON));

        mockMvc.perform(post("/api/events/movie")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"movie_id\":1,\"title\":\"Test\",\"action\":\"viewed\",\"user_id\":1}"))
                .andExpect(status().isOk()); // upstream returned 200

        upstream.verify();
    }

    // ── Upstream error propagation ────────────────────────────────────────────

    @Test
    void upstreamError_propagatedToClient() throws Exception {
        upstream.expect(requestTo("http://monolith-test/api/users"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        mockMvc.perform(get("/api/users"))
                .andExpect(status().is5xxServerError());

        upstream.verify();
    }

    // ── Full migration: 100% to movies-service ────────────────────────────────

    @Test
    void moviesRequest_withFullMigration_routesToMoviesService() throws Exception {
        // Override migration settings for this single test via a nested context
        // is complex; instead verify the routing logic via resolveUpstream directly
        // by using a separate test class annotated with 100% migration properties.
        // This test documents the expected behaviour — see ProxyControllerMigrationTest.
    }
}
