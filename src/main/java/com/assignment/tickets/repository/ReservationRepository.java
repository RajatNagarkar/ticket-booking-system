package com.assignment.tickets.repository;

import com.assignment.tickets.entity.Reservation;
import java.sql.Array;
import java.sql.SQLException;
import java.util.List;
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

    public Reservation insertConfirmed(UUID showId, String userId, List<String> seats, long amountPaise,
                                       String idempotencyKey, String requestHash) {
        return jdbc.queryForObject("""
                INSERT INTO reservations (show_id, user_id, seats, amount_paise, status, idempotency_key, request_hash)
                VALUES (?, ?, ?::text[], ?, 'confirmed', ?, ?)
                RETURNING *
                """, RESERVATION_MAPPER,
                showId, userId, seats.toArray(String[]::new), amountPaise, idempotencyKey, requestHash);
    }

    private static List<String> toList(Array array) throws SQLException {
        return List.of((String[]) array.getArray());
    }
}
