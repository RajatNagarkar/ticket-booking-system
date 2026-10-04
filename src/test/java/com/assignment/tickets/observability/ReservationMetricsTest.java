package com.assignment.tickets.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.assignment.tickets.dto.response.ReservationOutcome;
import com.assignment.tickets.dto.response.ReservationResponse;
import com.assignment.tickets.exception.SeatTakenException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class ReservationMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ReservationMetrics metrics = new ReservationMetrics(registry);
    private final UUID showId = UUID.randomUUID();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void confirmedReserveIsCountedAndLogged() {
        ReservationOutcome outcome = metrics.recordReserve(showId, () -> outcome(false));

        assertThat(count("reservations.confirmed")).isEqualTo(1);
        assertThat(declined("idempotent_replay")).isZero();
        assertThat(MDC.get("outcome")).isEqualTo("confirmed");
        assertThat(MDC.get("show_id")).isEqualTo(showId.toString());
        assertThat(MDC.get("reservation_id")).isEqualTo(outcome.reservation().reservationId().toString());
    }

    @Test
    void replayIsCountedAsDeclinedNotConfirmed() {
        metrics.recordReserve(showId, () -> outcome(true));

        assertThat(count("reservations.confirmed")).isZero();
        assertThat(declined("idempotent_replay")).isEqualTo(1);
        assertThat(MDC.get("outcome")).isEqualTo("idempotent_replay");
    }

    @Test
    void declineIsCountedByReasonAndRethrown() {
        SeatTakenException decline = new SeatTakenException(List.of("A1"));

        assertThatThrownBy(() -> metrics.recordReserve(showId, () -> {
            throw decline;
        })).isSameAs(decline);

        assertThat(declined("seat_taken")).isEqualTo(1);
        assertThat(count("reservations.confirmed")).isZero();
    }

    @Test
    void unexpectedFailureIsNotCountedAsADecline() {
        assertThatThrownBy(() -> metrics.recordReserve(showId, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(registry.find("reservations.declined").counters())
                .allSatisfy(c -> assertThat(c.count()).isZero());
        assertThat(count("reservations.confirmed")).isZero();
    }

    @Test
    void cancelIsCountedOnceAndRepeatIsOnlyLogged() {
        UUID reservationId = UUID.randomUUID();

        metrics.recordCancel(reservationId, () -> outcome(false));
        assertThat(MDC.get("outcome")).isEqualTo("cancelled");
        metrics.recordCancel(reservationId, () -> outcome(true));

        assertThat(count("reservations.cancelled")).isEqualTo(1);
        assertThat(MDC.get("outcome")).isEqualTo("already_cancelled");
        assertThat(MDC.get("reservation_id")).isEqualTo(reservationId.toString());
    }

    private double count(String name) {
        return registry.get(name).counter().count();
    }

    private double declined(String reason) {
        return registry.get("reservations.declined").tag("reason", reason).counter().count();
    }

    private ReservationOutcome outcome(boolean replayed) {
        return new ReservationOutcome(new ReservationResponse(UUID.randomUUID(), showId, "alice",
                List.of("A1"), 100, "confirmed"), replayed);
    }
}
