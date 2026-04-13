package com.cinemaabyss.proxy;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.Set;

@RestController
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    // Hop-by-hop заголовки не должны проксироваться (RFC 2616 §13.5.1)
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailers", "transfer-encoding", "upgrade", "host"
    );

    private final RestTemplate restTemplate;
    private final Random random = new Random();

    @Autowired
    public ProxyController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Value("${proxy.monolith-url}")
    private String monolithUrl;

    @Value("${proxy.movies-service-url}")
    private String moviesServiceUrl;

    @Value("${proxy.events-service-url}")
    private String eventsServiceUrl;

    @Value("${proxy.gradual-migration}")
    private boolean gradualMigration;

    @Value("${proxy.movies-migration-percent}")
    private int migrationPercent;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Boolean>> health() {
        return ResponseEntity.ok(Map.of("status", true));
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(HttpServletRequest request,
                                        @RequestBody(required = false) byte[] body) {
        String path = request.getRequestURI();
        String upstream = resolveUpstream(request.getMethod(), path);

        String query = request.getQueryString();
        String url = upstream + path + (query != null ? "?" + query : "");

        HttpHeaders headers = new HttpHeaders();
        Collections.list(request.getHeaderNames())
                .stream()
                .filter(name -> !HOP_BY_HOP_HEADERS.contains(name.toLowerCase()))
                .forEach(name -> headers.set(name, request.getHeader(name)));

        HttpEntity<byte[]> entity = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<byte[]> upstreamResponse = restTemplate.exchange(
                    url, HttpMethod.valueOf(request.getMethod()), entity, byte[].class);

            HttpHeaders responseHeaders = new HttpHeaders();
            upstreamResponse.getHeaders().forEach((name, values) -> {
                if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
                    responseHeaders.put(name, values);
                }
            });

            return ResponseEntity.status(upstreamResponse.getStatusCode())
                    .headers(responseHeaders)
                    .body(upstreamResponse.getBody());
        } catch (HttpStatusCodeException e) {
            HttpHeaders responseHeaders = new HttpHeaders();
            if (e.getResponseHeaders() != null) {
                e.getResponseHeaders().forEach((name, values) -> {
                    if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
                        responseHeaders.put(name, values);
                    }
                });
            }
            return ResponseEntity.status(e.getStatusCode())
                    .headers(responseHeaders)
                    .body(e.getResponseBodyAsByteArray());
        }
    }

    private String resolveUpstream(String method, String path) {
        if (path.startsWith("/api/events")) {
            log.info("[proxy] {} {} -> events-service", method, path);
            return eventsServiceUrl;
        }
        if (path.startsWith("/api/movies")) {
            if (gradualMigration && random.nextInt(100) < migrationPercent) {
                log.info("[proxy] {} {} -> movies-service ({}% migration)", method, path, migrationPercent);
                return moviesServiceUrl;
            }
            log.info("[proxy] {} {} -> monolith", method, path);
            return monolithUrl;
        }
        log.info("[proxy] {} {} -> monolith", method, path);
        return monolithUrl;
    }
}
