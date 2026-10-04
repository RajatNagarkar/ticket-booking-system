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
    void failedRefreshDropsStaleRowsAndCountsTheFailure() {
        UUID show = UUID.randomUUID();
        when(seats.countByShowAndStatus(any())).thenReturn(List.of(new SeatCount(show, "available", 5)));
        gauges.refresh();
        assertThat(registry.find("seats").tag("show", show.toString()).gauge().value()).isEqualTo(5);

        when(seats.countByShowAndStatus(any())).thenThrow(new DataAccessResourceFailureException("db down"));
        gauges.refresh();

        assertThat(registry.find("seats").gauges()).isEmpty();
        assertThat(registry.get("seats.gauge.refresh.failures").counter().count()).isEqualTo(1);
    }
}
