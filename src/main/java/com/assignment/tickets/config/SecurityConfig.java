package com.assignment.tickets.config;

import com.assignment.tickets.dto.response.ErrorResponse;
import com.assignment.tickets.observability.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless JWT bearer auth. The caller's identity is the token's {@code sub} claim and
 * its roles come from the {@code roles} claim; nothing in a request body can change either.
 */
@Configuration
public class SecurityConfig {

    public static final String ROLES_CLAIM = "roles";

    @Bean
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
