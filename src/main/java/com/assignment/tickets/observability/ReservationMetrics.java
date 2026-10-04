package com.assignment.tickets.observability;

import com.assignment.tickets.dto.response.ReservationOutcome;
import com.assignment.tickets.exception.ApiException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Records reservation outcomes as metrics and as the request's log context.
 * <p>
 * Controllers pass the service call in, so recording happens after the service's transaction
 * has committed (or rolled back) and the counters cannot drift from what the database holds.
 * Exposed as {@code reservations_confirmed_total}, {@code reservations_declined_total{reason}}
 * and {@code reservations_cancelled_total}.
 */
@Component
public class ReservationMetrics {

    public static final String IDEMPOTENT_REPLAY = "idempotent_replay";

    private final MeterRegistry registry;
    private final Counter confirmed;
    private final Counter cancelled;

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.confirmed = Counter.builder("reservations.confirmed")
                .description("Reservations committed")
                .register(registry);
        this.cancelled = Counter.builder("reservations.cancelled")
                .description("Reservations cancelled")
                .register(registry);
        // Pre-register the common reasons so they show as 0 before the first decline.
        for (String reason : new String[] {"seat_taken", "per_user_limit", IDEMPOTENT_REPLAY, "idempotency_key_reused"}) {
            declinedCounter(reason);
        }
    }

    /**
     * Runs a reserve call and records it: confirmed, idempotent replay, or declined with the
     * error code. Declines are rethrown unchanged for the exception handler.
     */
    public ReservationOutcome recordReserve(UUID showId, Supplier<ReservationOutcome> reserve) {
        RequestContext.put(RequestContext.SHOW_ID, showId);
        ReservationOutcome outcome;
        try {
            outcome = reserve.get();
        } catch (ApiException e) {
            declinedCounter(e.getCode()).increment();
            throw e;
        }
        RequestContext.put(RequestContext.RESERVATION_ID, outcome.reservation().reservationId());
        if (outcome.replayed()) {
            declinedCounter(IDEMPOTENT_REPLAY).increment();
            RequestContext.put(RequestContext.OUTCOME, IDEMPOTENT_REPLAY);
        } else {
            confirmed.increment();
            RequestContext.put(RequestContext.OUTCOME, "confirmed");
        }
        return outcome;
    }

    /** Runs a cancel call and records it; repeating a cancel is logged but not counted again. */
    public ReservationOutcome recordCancel(UUID reservationId, Supplier<ReservationOutcome> cancel) {
        RequestContext.put(RequestContext.RESERVATION_ID, reservationId);
        ReservationOutcome outcome = cancel.get();
        if (outcome.replayed()) {
            RequestContext.put(RequestContext.OUTCOME, "already_cancelled");
        } else {
            cancelled.increment();
            RequestContext.put(RequestContext.OUTCOME, "cancelled");
        }
        return outcome;
    }

    private Counter declinedCounter(String reason) {
        return Counter.builder("reservations.declined")
                .description("Reservation requests that did not create a reservation, by reason")
                .tag("reason", reason)
                .register(registry);
    }
}
