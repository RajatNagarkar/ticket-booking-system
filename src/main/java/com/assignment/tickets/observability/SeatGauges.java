package com.assignment.tickets.observability;

import com.assignment.tickets.entity.SeatCount;
import com.assignment.tickets.repository.SeatRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Seat metrics read from the database at scrape time, not tracked in memory, so they always
 * match GET /shows/{id} and agree across any number of instances. Only shows created within
 * {@code app.metrics.seat-gauge-window} are reported.
 * <ul>
 *   <li>{@code seats{show, status}}: seats per show and status.</li>
 *   <li>{@code seats_invariant_violations}: shows where available + held + confirmed differs
 *       from total_seats. Always 0 by construction; anything else is a paging alert.</li>
 * </ul>
 */
@Slf4j
@Component
public class SeatGauges {

    private final SeatRepository seats;
    private final Duration window;
    private final MultiGauge gauge;
    private final Counter refreshFailures;
    private final AtomicReference<Double> invariantViolations = new AtomicReference<>(0.0);

    public SeatGauges(SeatRepository seats, MeterRegistry registry,
                      @Value("${app.metrics.seat-gauge-window:PT24H}") Duration window) {
        this.seats = seats;
        this.window = window;
        this.gauge = MultiGauge.builder("seats")
                .description("Seats per show by status, read from the database at scrape time")
                .register(registry);
        this.refreshFailures = Counter.builder("seats.gauge.refresh.failures")
                .description("Scrapes where seat counts could not be read from the database")
                .register(registry);
        Gauge.builder("seats.invariant.violations", invariantViolations, AtomicReference::get)
                .description("Shows where available + held + confirmed != total_seats (expected 0)")
                .register(registry);
    }

    /** Called right before each Prometheus scrape. */
    public void refresh() {
        List<SeatCount> counts;
        try {
            counts = seats.countByShowAndStatus(Instant.now().minus(window));
        } catch (RuntimeException e) {
            // Drop the values rather than serve stale ones: a missing value is visible, a wrong one is not.
            gauge.register(List.of(), true);
            invariantViolations.set(Double.NaN);
            refreshFailures.increment();
            log.warn("Could not refresh seat gauges", e);
            return;
        }
        gauge.register(counts.stream()
                .<MultiGauge.Row<?>>map(c -> MultiGauge.Row.of(
                        Tags.of("show", c.showId().toString(), "status", c.status()), c.count()))
                .toList(), true);
        recordInvariant(counts);
    }

    private void recordInvariant(List<SeatCount> counts) {
        Map<UUID, List<SeatCount>> byShow = counts.stream().collect(Collectors.groupingBy(SeatCount::showId));
        List<UUID> violating = byShow.entrySet().stream()
                .filter(e -> e.getValue().stream().mapToLong(SeatCount::count).sum() != e.getValue().get(0).totalSeats())
                .map(Map.Entry::getKey)
                .toList();
        invariantViolations.set((double) violating.size());
        if (!violating.isEmpty()) {
            log.error("Seat invariant violated for shows {}", violating);
        }
    }
}
