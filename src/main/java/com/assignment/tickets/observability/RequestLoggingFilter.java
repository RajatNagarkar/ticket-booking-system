package com.assignment.tickets.observability;

import static net.logstash.logback.argument.StructuredArguments.kv;

import com.assignment.tickets.controller.MetricsScrapeController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Outermost filter (before Spring Security): assigns the request id, echoes it in the
 * response, and writes one structured access-log line per request when it completes.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";

    /** Client-supplied ids are reused only if short and safe to log; otherwise a new one is generated. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader(REQUEST_ID_HEADER);
        String requestId = supplied != null && SAFE_ID.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
        RequestContext.put(RequestContext.REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            int status = response.getStatus();
            Object[] fields = {kv("method", request.getMethod()), kv("path", request.getRequestURI()),
                    kv("status", status), kv("duration_ms", durationMs)};
            if (status >= 500) {
                log.error("request completed", fields);
            } else {
                log.info("request completed", fields);
            }
            MDC.clear();
        }
    }

    /** Health probes and metric scrapes would drown out real traffic. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.contains("/actuator/") || uri.endsWith(MetricsScrapeController.PATH);
    }
}
