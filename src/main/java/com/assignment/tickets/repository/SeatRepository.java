package com.assignment.tickets.repository;

import com.assignment.tickets.entity.Seat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SeatRepository {

    private static final RowMapper<Seat> SEAT_MAPPER =
            (rs, i) -> new Seat(rs.getString("seat_no"), rs.getString("status"));

    private final JdbcTemplate jdbc;

    /** Inserts every seat as available in a single round trip (unnest over a text[] parameter). */
    public void insertAll(UUID showId, List<String> seatNos) {
        jdbc.update("""
                INSERT INTO seats (show_id, seat_no)
                SELECT ?, unnest(?::text[])
                """, showId, seatNos.toArray(String[]::new));
    }

    /**
     * Row-locks the requested seats. ORDER BY makes every transaction acquire locks in the
     * same order, so overlapping multi-seat requests queue behind each other instead of
     * deadlocking. A waiter sees the latest committed status once it gets the lock.
     */
    public List<Seat> lockForUpdate(UUID showId, List<String> seatNos) {
        return jdbc.query("""
                SELECT seat_no, status
                FROM seats
                WHERE show_id = ? AND seat_no = ANY(?::text[])
                ORDER BY seat_no
                FOR UPDATE
                """, SEAT_MAPPER, showId, seatNos.toArray(String[]::new));
    }

    /**
     * The atomic decision: only seats still available are taken. Returns the number of
     * seats confirmed; anything less than requested means another reservation won.
     */
    public int confirm(UUID showId, List<String> seatNos, UUID reservationId, String userId) {
        return jdbc.update("""
                UPDATE seats
                SET status = 'confirmed', reservation_id = ?, user_id = ?
                WHERE show_id = ? AND seat_no = ANY(?::text[]) AND status = 'available'
                """, reservationId, userId, showId, seatNos.toArray(String[]::new));
    }

    /**
     * Returns a reservation's seats to available. Scoped by reservation_id, so it can only
     * ever touch seats this reservation owns, never a seat since confirmed to someone else.
     */
    public int release(UUID reservationId) {
        return jdbc.update("""
                UPDATE seats
                SET status = 'available', reservation_id = NULL, user_id = NULL
                WHERE reservation_id = ? AND status = 'confirmed'
                """, reservationId);
    }

    /** One statement, so all statuses come from the same snapshot and the counts reconcile. */
    public List<Seat> findByShow(UUID showId) {
        return jdbc.query("""
                SELECT seat_no, status
                FROM seats
                WHERE show_id = ?
                ORDER BY seat_no
                """, SEAT_MAPPER, showId);
    }
}
