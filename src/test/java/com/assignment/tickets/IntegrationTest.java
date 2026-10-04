package com.assignment.tickets;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Boots the full app on a random port against a real Postgres. The container is
 * shared by every test class (started once, stopped when the JVM exits).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTest {

    @Autowired
    protected TestRestTemplate http;

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    // Flyway logs in with its own credentials in production; reuse the container's here.
    @DynamicPropertySource
    static void flywayCredentials(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    // java.net.http client: reads 4xx bodies on POST (HttpURLConnection fails on 401) and pools connections.
    @BeforeEach
    void useJdkHttpClient() {
        http.getRestTemplate().setRequestFactory(new JdkClientHttpRequestFactory());
    }
}
