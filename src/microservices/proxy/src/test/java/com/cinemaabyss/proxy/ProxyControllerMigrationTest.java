package com.cinemaabyss.proxy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests proxy routing with gradual migration fully enabled (100%).
 * All /api/movies requests must go to movies-service, not monolith.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "proxy.monolith-url=http://monolith-test",
        "proxy.movies-service-url=http://movies-test",
        "proxy.events-service-url=http://events-test",
        "proxy.gradual-migration=true",
        "proxy.movies-migration-percent=100"
})
class ProxyControllerMigrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RestTemplate restTemplate;

    MockRestServiceServer upstream;

    @BeforeEach
    void setUp() {
        upstream = MockRestServiceServer.createServer(restTemplate);
    }

    @RepeatedTest(5)
    void moviesRequest_withFullMigration_alwaysRoutesToMoviesService() throws Exception {
        // With 100% migration, every request must go to movies-service
        upstream.expect(requestTo("http://movies-test/api/movies"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/movies"))
                .andExpect(status().isOk());

        upstream.verify();
        upstream.reset();
    }
}
