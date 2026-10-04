package com.assignment.tickets;

import com.assignment.tickets.service.TokenService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots the full app on a random port against a real Postgres. The container is
 * shared by every test class (started once, stopped when the JVM exits). Metrics export
 * is on (Spring Boot disables it in tests by default) so the Prometheus endpoint exists.
 */
@AutoConfigureObservability
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    protected static final String JWT_SECRET = "test-secret-at-least-32-bytes-long!!";
    protected static final String ADMIN = "admin";

    @Autowired
    protected TestRestTemplate http;

    @Autowired
    protected TokenService tokens;

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Flyway logs in with its own credentials in production; reuse the container's here.
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("app.jwt.secret", () -> JWT_SECRET);
    }

    // java.net.http client: reads 4xx bodies on POST (HttpURLConnection fails on 401) and pools connections.
    @BeforeEach
    void useJdkHttpClient() {
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }

    /** POST as {@code userId} ({@link #ADMIN} gets the admin role); a null user sends no token. */
    protected ResponseEntity<JsonNode> post(String path, Object body, String userId) {
        return userId == null
                ? postWithToken(path, body, null)
                : postWithToken(path, body, tokens.issue(userId, ADMIN.equals(userId)).accessToken());
    }

    protected ResponseEntity<JsonNode> postWithToken(String path, Object body, String rawToken) {
        HttpHeaders headers = new HttpHeaders();
        if (rawToken != null) {
            headers.setBearerAuth(rawToken);
        }
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
