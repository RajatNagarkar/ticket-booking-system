package com.assignment.tickets.service;

import com.assignment.tickets.dto.request.ReserveRequest;
import com.assignment.tickets.dto.response.ReservationResponse;
import com.assignment.tickets.entity.Reservation;
import com.assignment.tickets.entity.Seat;
import com.assignment.tickets.entity.Show;
import com.assignment.tickets.exception.BadRequestException;
import com.assignment.tickets.exception.SeatTakenException;
import com.assignment.tickets.exception.ShowNotFoundException;
import com.assignment.tickets.exception.UnknownSeatException;
import com.assignment.tickets.repository.ReservationRepository;
import com.assignment.tickets.repository.SeatRepository;
import com.assignment.tickets.repository.ShowRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
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

    /**
     * All-or-nothing: either every requested seat is confirmed to this user, or the
     * transaction rolls back (including the reservation row) and nothing changes.
     */
    @Transactional
    public ReservationResponse reserve(UUID showId, String userId, ReserveRequest request) {
        List<String> seatNos = request.seats().stream().sorted().toList();
        if (new HashSet<>(seatNos).size() != seatNos.size()) {
            throw new BadRequestException("duplicate_seats", "Seat numbers must be unique");
        }
        Show show = shows.findById(showId).orElseThrow(() -> new ShowNotFoundException(showId));
        long amountPaise = Math.multiplyExact(show.pricePaise(), seatNos.size());

        // Inserted first because seats.reservation_id references it; rolled back on any decline.
        Reservation reservation = reservations.insertConfirmed(showId, userId, seatNos, amountPaise,
                request.idempotencyKey(), requestHash(showId, seatNos));

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
        return ReservationResponse.from(reservation);
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
