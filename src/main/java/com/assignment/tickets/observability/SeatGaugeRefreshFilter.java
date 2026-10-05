package com.assignment.tickets.observability;

import com.assignment.tickets.controller.MetricsScrapeController;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Re-reads seat counts from the database just before each Prometheus scrape is rendered. */
@Component
@RequiredArgsConstructor
public class SeatGaugeRefreshFilter extends OncePerRequestFilter {

    private final SeatGauges seatGauges;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        seatGauges.refresh();
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.endsWith("/actuator/prometheus") && !uri.endsWith(MetricsScrapeController.PATH);
    }
}
