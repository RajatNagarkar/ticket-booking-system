package com.assignment.tickets.controller;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The same Prometheus text as {@code /actuator/prometheus}, behind Basic auth (SecurityConfig).
 * Grafana Cloud's Metrics Endpoint only scrapes URLs that reject unauthenticated requests; the
 * actuator endpoint stays public for the burst script and anyone checking the live service.
 */
@RestController
@RequiredArgsConstructor
public class MetricsScrapeController {

    public static final String PATH = "/internal/metrics";

    private final PrometheusMeterRegistry registry;

    @GetMapping(path = PATH, produces = "text/plain;version=0.0.4;charset=utf-8")
    public String scrape() {
        return registry.scrape();
    }
}
