package com.assignment.tickets.observability;

import com.assignment.tickets.repository.SeatRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@code seats{show, status}}: seat counts read from the database, not tracked in memory,
 * so the gauge always matches GET /shows/{id} and agrees across any number of instances.
 * Only shows created within {@code app.metrics.seat-gauge-window} are reported.
 */
@Slf4j
@Component
public class SeatGauges {

    private final SeatRepository seats;
    private final Duration window;
    private final MultiGauge gauge;
    private final Counter refreshFailures;

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
    }

    /** Called right before each Prometheus scrape. */
    public void refresh() {
        try {
            List<MultiGauge.Row<?>> rows = seats.countByShowAndStatus(Instant.now().minus(window)).stream()
                    .<MultiGauge.Row<?>>map(c -> MultiGauge.Row.of(
                            Tags.of("show", c.showId().toString(), "status", c.status()), c.count()))
                    .toList();
            gauge.register(rows, true);
        } catch (RuntimeException e) {
            // Drop the rows rather than serve stale counts: a missing series is visible, a wrong one is not.
            gauge.register(List.of(), true);
            refreshFailures.increment();
            log.warn("Could not refresh seat gauges", e);
        }
    }
}
