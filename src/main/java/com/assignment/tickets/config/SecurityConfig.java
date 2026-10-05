package com.assignment.tickets.config;

import com.assignment.tickets.controller.MetricsScrapeController;
import com.assignment.tickets.dto.response.ErrorResponse;
import com.assignment.tickets.observability.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT bearer auth. The caller's identity is the token's {@code sub} claim and
 * its roles come from the {@code roles} claim; nothing in a request body can change either.
 */
@Configuration
public class SecurityConfig {

    public static final String ROLES_CLAIM = "roles";

    /**
     * Basic auth for the Grafana Cloud scrape endpoint only, checked before the JWT chain.
     * Without METRICS_PASSWORD the credentials are random, so every request is rejected.
     */
    @Bean
    @Order(1)
    SecurityFilterChain metricsScrapeFilterChain(HttpSecurity http, ObjectMapper objectMapper,
                                                 @Value("${app.metrics.scrape.user}") String user,
                                                 @Value("${app.metrics.scrape.password}") String password)
            throws Exception {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        String secret = password.isBlank() ? UUID.randomUUID().toString() : password;
        var users = new InMemoryUserDetailsManager(
                User.withUsername(user).password(encoder.encode(secret)).roles("METRICS").build());
        return http
                .securityMatcher(MetricsScrapeController.PATH)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .userDetailsService(users)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("METRICS"))
                .httpBasic(basic -> basic.authenticationEntryPoint((request, response, e) -> {
                    response.setHeader("WWW-Authenticate", "Basic realm=\"metrics\"");
                    writeError(response, objectMapper, HttpStatus.UNAUTHORIZED,
                            "unauthenticated", "Missing or invalid credentials");
                }))
                .build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/token").permitAll()
                        .requestMatchers(HttpMethod.GET, "/shows/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/shows").hasRole("ADMIN")
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(rolesConverter()))
                        .authenticationEntryPoint((request, response, e) -> {
                            response.setHeader("WWW-Authenticate", "Bearer");
                            writeError(response, objectMapper, HttpStatus.UNAUTHORIZED,
                                    "unauthenticated", "Missing or invalid bearer token");
                        })
                        .accessDeniedHandler((request, response, e) -> writeError(response, objectMapper,
                                HttpStatus.FORBIDDEN, "forbidden", "Not allowed for this role")))
                .build();
    }

    /** {@code "roles": ["ADMIN", "USER"]} becomes ROLE_ADMIN, ROLE_USER. */
    private static JwtAuthenticationConverter rolesConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private static void writeError(HttpServletResponse response, ObjectMapper objectMapper, HttpStatus status,
                                   String code, String message) throws IOException {
        RequestContext.put(RequestContext.OUTCOME, code);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ErrorResponse(code, message));
    }
}
