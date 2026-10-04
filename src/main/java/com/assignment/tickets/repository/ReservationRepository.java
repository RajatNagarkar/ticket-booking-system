package com.assignment.tickets.repository;

import com.assignment.tickets.entity.Reservation;
import java.sql.Array;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ReservationRepository {

    private static final RowMapper<Reservation> RESERVATION_MAPPER = (rs, i) -> new Reservation(
            rs.getObject("id", UUID.class),
            rs.getObject("show_id", UUID.class),
            rs.getString("user_id"),
            toList(rs.getArray("seats")),
            rs.getLong("amount_paise"),
            rs.getString("status"),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    /**
     * Inserts the reservation unless this user already used this idempotency key.
     * A concurrent insert with the same key blocks on the unique index until the first
     * transaction finishes: if it committed, this returns empty; if it rolled back, this
     * insert goes ahead. Either way exactly one reservation exists per (user, key).
     */
    public Optional<Reservation> insertIfAbsent(UUID showId, String userId, List<String> seats, long amountPaise,
                                                String idempotencyKey, String requestHash) {
        return jdbc.query("""
                INSERT INTO reservations (show_id, user_id, seats, amount_paise, status, idempotency_key, request_hash)
                VALUES (?, ?, ?::text[], ?, 'confirmed', ?, ?)
                ON CONFLICT (user_id, idempotency_key) DO NOTHING
                RETURNING *
                """, RESERVATION_MAPPER,
                showId, userId, seats.toArray(String[]::new), amountPaise, idempotencyKey, requestHash)
                .stream().findFirst();
    }

    public Optional<Reservation> findByUserAndKey(String userId, String idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM reservations
                WHERE user_id = ? AND idempotency_key = ?
                """, RESERVATION_MAPPER, userId, idempotencyKey).stream().findFirst();
    }

    public Optional<Reservation> findById(UUID id) {
        return jdbc.query("SELECT * FROM reservations WHERE id = ?", RESERVATION_MAPPER, id)
                .stream().findFirst();
    }

    /**
     * Cancels the reservation only if it belongs to this user and is still confirmed.
     * The row lock makes concurrent cancels of the same reservation serialize: the second
     * re-checks status = 'confirmed' after the first commits and matches nothing.
     */
    public Optional<Reservation> cancel(UUID id, String userId) {
        return jdbc.query("""
                UPDATE reservations
                SET status = 'cancelled', cancelled_at = now()
                WHERE id = ? AND user_id = ? AND status = 'confirmed'
                RETURNING *
                """, RESERVATION_MAPPER, id, userId).stream().findFirst();
    }

    private static List<String> toList(Array array) throws SQLException {
        return List.of((String[]) array.getArray());
    }
}
