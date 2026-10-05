package com.assignment.tickets.service;

import com.assignment.tickets.dto.request.ReserveRequest;
import com.assignment.tickets.dto.response.ReservationOutcome;
import com.assignment.tickets.dto.response.ReservationResponse;
import com.assignment.tickets.entity.Reservation;
import com.assignment.tickets.entity.Seat;
import com.assignment.tickets.entity.SeatConfirmation;
import com.assignment.tickets.entity.SeatState;
import com.assignment.tickets.entity.Show;
import com.assignment.tickets.exception.BadRequestException;
import com.assignment.tickets.exception.IdempotencyKeyReusedException;
import com.assignment.tickets.exception.PerUserLimitException;
import com.assignment.tickets.exception.ReservationNotFoundException;
import com.assignment.tickets.exception.SeatTakenException;
import com.assignment.tickets.exception.ShowNotFoundException;
import com.assignment.tickets.exception.UnknownSeatException;
import com.assignment.tickets.repository.QuotaRepository;
import com.assignment.tickets.repository.ReservationRepository;
import com.assignment.tickets.repository.SeatRepository;
import com.assignment.tickets.repository.ShowRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final ReservationRepository reservations;
    private final QuotaRepository quotas;
    private final TransactionRetry retry;

    /**
     * All-or-nothing: either every requested seat is confirmed to this user, or the
     * transaction rolls back (including the reservation row) and nothing changes.
     * <p>
     * Idempotent per (user, idempotency key): a retry with the same seats returns the
     * original reservation; the same key with different seats is rejected. A declined
     * attempt rolls back, so its key stays free for a later retry. A request for exactly the
     * seats the user already holds in one confirmed reservation returns that reservation,
     * whatever key it carries.
     * <p>
     * Locks are always taken in the same order (idempotency key, then the user's quota row,
     * then seats sorted by seat_no), so concurrent reservations cannot deadlock. Cancel
     * follows the same quota-then-seats order. If PostgreSQL still aborts the transaction
     * (deadlock or serialization failure), it is retried from scratch in a new transaction.
     */
    public ReservationOutcome reserve(UUID showId, String userId, ReserveRequest request) {
        try {
            return retry.inTransaction(() -> reserveOnce(showId, userId, request));
        } catch (SameSeatsAlreadyReservedException e) {
            // The new attempt was rolled back; hand back the reservation the user already holds.
            return new ReservationOutcome(ReservationResponse.from(e.existing()), true);
        }
    }

    private ReservationOutcome reserveOnce(UUID showId, String userId, ReserveRequest request) {
        List<String> seatNos = request.seats().stream().sorted().toList();
        if (new HashSet<>(seatNos).size() != seatNos.size()) {
            throw new BadRequestException("duplicate_seats", "Seat numbers must be unique");
        }
        Show show = shows.findById(showId).orElseThrow(() -> new ShowNotFoundException(showId));
        long amountPaise = Math.multiplyExact(show.pricePaise(), seatNos.size());

        // Inserted first: it claims the idempotency key, and seats.reservation_id references it.
        String requestHash = requestHash(showId, seatNos);
        Optional<Reservation> inserted = reservations.insertIfAbsent(showId, userId, seatNos, amountPaise,
                request.idempotencyKey(), requestHash);
        if (inserted.isEmpty()) {
            return replay(userId, request.idempotencyKey(), requestHash);
        }
        Reservation reservation = inserted.get();

        // Lock-free read of the committed seat state, to decline early. Most stampede requests end
        // here, without touching the quota or waiting for seat locks.
        declineEarly(userId, seatNos, seats.currentState(showId, seatNos));

        // After the replay check, so a retry never counts against the limit twice.
        if (seatNos.size() > show.perUserLimit()
                || !quotas.tryAcquire(showId, userId, seatNos.size(), show.perUserLimit())) {
            throw new PerUserLimitException(show.perUserLimit());
        }

        SeatConfirmation result = seats.lockAndConfirm(showId, seatNos, reservation.id(), userId);
        if (result.found() < seatNos.size()) {
            throw new IllegalStateException("Seats disappeared from show " + showId);
        }
        if (result.confirmed() < seatNos.size()) {
            throw new SeatTakenException(result.unavailable());
        }
        return new ReservationOutcome(ReservationResponse.from(reservation), false);
    }

    /**
     * Unknown seats, the user's own existing reservation, or seats already taken, all decided
     * from committed state. Seats that look available still go through the locked decision.
     */
    private void declineEarly(String userId, List<String> seatNos, List<SeatState> state) {
        if (state.size() < seatNos.size()) {
            Set<String> found = state.stream().map(SeatState::seatNo).collect(Collectors.toSet());
            throw new UnknownSeatException(seatNos.stream().filter(s -> !found.contains(s)).toList());
        }
        List<String> unavailable = state.stream()
                .filter(s -> !Seat.AVAILABLE.equals(s.status()))
                .map(SeatState::seatNo)
                .sorted()
                .toList();
        if (unavailable.isEmpty()) {
            return;
        }
        // Natural idempotency: a retry that lost its key (or was sent with a new one) after the
        // first attempt committed gets that reservation back instead of seat_taken for its own
        // seats, even if the user is at their limit.
        Set<UUID> owners = state.stream().map(SeatState::reservationId).collect(Collectors.toSet());
        if (unavailable.size() == seatNos.size() && owners.size() == 1
                && state.stream().allMatch(s -> userId.equals(s.userId()))) {
            reservations.findById(owners.iterator().next())
                    .filter(r -> Reservation.CONFIRMED.equals(r.status()) && r.seats().equals(seatNos))
                    .ifPresent(existing -> {
                        throw new SameSeatsAlreadyReservedException(existing);
                    });
        }
        throw new SeatTakenException(unavailable);
    }

    /**
     * Owner-only and idempotent: cancelling an already-cancelled reservation returns it
     * unchanged (flagged as replayed). Another user's reservation looks exactly like a
     * missing one (404). Retried like reserve on a transient database abort.
     */
    public ReservationOutcome cancel(UUID reservationId, String userId) {
        return retry.inTransaction(() -> cancelOnce(reservationId, userId));
    }

    private ReservationOutcome cancelOnce(UUID reservationId, String userId) {
        Optional<Reservation> cancelled = reservations.cancel(reservationId, userId);
        if (cancelled.isEmpty()) {
            return reservations.findById(reservationId)
                    .filter(r -> r.userId().equals(userId))
                    .map(r -> new ReservationOutcome(ReservationResponse.from(r), true))
                    .orElseThrow(() -> new ReservationNotFoundException(reservationId));
        }
        Reservation reservation = cancelled.get();
        int seatCount = reservation.seats().size();

        // Quota before seats: the same lock order as reserve, so the two cannot deadlock.
        quotas.release(reservation.showId(), userId, seatCount);
        int released = seats.release(reservation.id());
        if (released != seatCount) {
            throw new IllegalStateException("Reservation " + reservation.id() + " owned " + released
                    + " seats, expected " + seatCount);
        }
        return new ReservationOutcome(ReservationResponse.from(reservation), false);
    }

    private ReservationOutcome replay(String userId, String idempotencyKey, String requestHash) {
        Reservation existing = reservations.findByUserAndKey(userId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Idempotency conflict without a committed row"));
        if (!existing.requestHash().equals(requestHash)) {
            throw new IdempotencyKeyReusedException();
        }
        return new ReservationOutcome(ReservationResponse.from(existing), true);
    }

    /** Identifies the request body for idempotency: same show and same seats, in any order. */
    private static String requestHash(UUID showId, List<String> sortedSeatNos) {
        String canonical = showId + ":" + String.join(",", sortedSeatNos);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
