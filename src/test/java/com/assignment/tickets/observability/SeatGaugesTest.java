package com.assignment.tickets.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.assignment.tickets.entity.SeatCount;
import com.assignment.tickets.repository.SeatRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class SeatGaugesTest {

    private final SeatRepository seats = mock(SeatRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final SeatGauges gauges = new SeatGauges(seats, registry, Duration.ofHours(24));

    @Test
    void reportsSeatsPerStatusAndNoViolationWhenCountsAddUp() {
        UUID show = UUID.randomUUID();
        when(seats.countByShowAndStatus(any())).thenReturn(counts(show, 5, 3, 0, 2));

        gauges.refresh();

        assertThat(registry.get("seats").tags("show", show.toString(), "status", "available").gauge().value())
                .isEqualTo(3);
        assertThat(registry.get("seats").tags("show", show.toString(), "status", "confirmed").gauge().value())
                .isEqualTo(2);
        assertThat(violations()).isZero();
    }

    @Test
    void countsShowsWhoseSeatsDoNotAddUpToTheTotal() {
        UUID healthy = UUID.randomUUID();
        UUID broken = UUID.randomUUID();
        when(seats.countByShowAndStatus(any())).thenReturn(concat(
                counts(healthy, 4, 2, 0, 2),
                counts(broken, 4, 2, 0, 1)));   // one seat missing

        gauges.refresh();

        assertThat(violations()).isEqualTo(1);
    }

    @Test
    void failedRefreshDropsStaleValuesAndCountsTheFailure() {
        UUID show = UUID.randomUUID();
        when(seats.countByShowAndStatus(any())).thenReturn(counts(show, 5, 5, 0, 0));
        gauges.refresh();

        when(seats.countByShowAndStatus(any())).thenThrow(new DataAccessResourceFailureException("db down"));
        gauges.refresh();

        assertThat(registry.find("seats").gauges()).isEmpty();
        assertThat(violations()).isNaN();
        assertThat(registry.get("seats.gauge.refresh.failures").counter().count()).isEqualTo(1);
    }

    private double violations() {
        return registry.get("seats.invariant.violations").gauge().value();
    }

    private static List<SeatCount> counts(UUID show, int total, long available, long held, long confirmed) {
        return List.of(new SeatCount(show, total, "available", available),
                new SeatCount(show, total, "held", held),
                new SeatCount(show, total, "confirmed", confirmed));
    }

    private static List<SeatCount> concat(List<SeatCount> a, List<SeatCount> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream()).toList();
    }
}
