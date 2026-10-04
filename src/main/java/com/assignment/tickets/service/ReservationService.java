package com.assignment.tickets.service;

import com.assignment.tickets.dto.request.ReserveRequest;
import com.assignment.tickets.dto.response.ReservationOutcome;
import com.assignment.tickets.dto.response.ReservationResponse;
import com.assignment.tickets.entity.Reservation;
import com.assignment.tickets.entity.Seat;
import com.assignment.tickets.entity.Show;
import com.assignment.tickets.exception.BadRequestException;
import com.assignment.tickets.exception.IdempotencyKeyReusedException;
import com.assignment.tickets.exception.PerUserLimitException;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ShowRepository shows;
    private final SeatRepository seats;
    private final ReservationRepository reservations;
    private final QuotaRepository quotas;

    /**
     * All-or-nothing: either every requested seat is confirmed to this user, or the
     * transaction rolls back (including the reservation row) and nothing changes.
     * <p>
     * Idempotent per (user, idempotency key): a retry with the same seats returns the
     * original reservation; the same key with different seats is rejected. A declined
     * attempt rolls back, so its key stays free for a later retry.
     * <p>
     * Locks are always taken in the same order (idempotency key, then the user's quota row,
     * then seats sorted by seat_no), so concurrent reservations cannot deadlock.
     */
    @Transactional
    public ReservationOutcome reserve(UUID showId, String userId, ReserveRequest request) {
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

        // After the replay check, so a retry never counts against the limit twice.
        if (seatNos.size() > show.perUserLimit()
                || !quotas.tryAcquire(showId, userId, seatNos.size(), show.perUserLimit())) {
            throw new PerUserLimitException(show.perUserLimit());
        }

        List<Seat> locked = seats.lockForUpdate(showId, seatNos);
        if (locked.size() < seatNos.size()) {
            Set<String> found = new HashSet<>(locked.stream().map(Seat::seatNo).toList());
            throw new UnknownSeatException(seatNos.stream().filter(s -> !found.contains(s)).toList());
        }

        int confirmed = seats.confirm(showId, seatNos, reservation.id(), userId);
        if (confirmed < seatNos.size()) {
            throw new SeatTakenException(locked.stream()
                    .filter(s -> !Seat.AVAILABLE.equals(s.status()))
                    .map(Seat::seatNo)
                    .toList());
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
