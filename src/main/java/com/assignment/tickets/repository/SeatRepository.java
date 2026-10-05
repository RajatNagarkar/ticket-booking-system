package com.assignment.tickets.repository;

import com.assignment.tickets.entity.Seat;
import com.assignment.tickets.entity.SeatConfirmation;
import com.assignment.tickets.entity.SeatCount;
import com.assignment.tickets.entity.SeatState;
import java.sql.Timestamp;
import java.time.Instant;
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
     * Committed status and owner of the requested seats, without taking locks. Used only to
     * decline early (a seat already confirmed stays taken until its owner cancels); the decision
     * to take a seat is always made by {@link #lockAndConfirm}.
     */
    public List<SeatState> currentState(UUID showId, List<String> seatNos) {
        return jdbc.query("""
                SELECT seat_no, status, user_id, reservation_id
                FROM seats
                WHERE show_id = ? AND seat_no = ANY(?::text[])
                """, (rs, i) -> new SeatState(rs.getString("seat_no"), rs.getString("status"),
                rs.getString("user_id"), rs.getObject("reservation_id", UUID.class)),
                showId, seatNos.toArray(String[]::new));
    }

    /**
     * The atomic decision, in one round trip:
     * <ol>
     *   <li>{@code locked}: row-lock the requested seats in seat_no order. Every transaction locks
     *       in the same order, so overlapping multi-seat requests queue instead of deadlocking.
     *       A transaction that waited sees the latest committed status once it holds the lock.</li>
     *   <li>{@code taken}: confirm only seats that are still available. The guard is re-checked
     *       against the latest row version, so of N racers for one seat exactly one updates it.</li>
     * </ol>
     * Fewer confirmed than requested means another reservation won; the caller rolls back.
     */
    public SeatConfirmation lockAndConfirm(UUID showId, List<String> seatNos, UUID reservationId, String userId) {
        String[] seats = seatNos.toArray(String[]::new);
        return jdbc.queryForObject("""
                WITH locked AS (
                    SELECT seat_no, status
                    FROM seats
                    WHERE show_id = ? AND seat_no = ANY(?::text[])
                    ORDER BY seat_no
                    FOR UPDATE
                ), taken AS (
                    UPDATE seats s
                    SET status = 'confirmed', reservation_id = ?, user_id = ?
                    FROM locked l
                    WHERE s.show_id = ? AND s.seat_no = l.seat_no
                      AND l.status = 'available' AND s.status = 'available'
                    RETURNING s.seat_no
                )
                SELECT (SELECT count(*) FROM locked) AS found,
                       (SELECT count(*) FROM taken) AS confirmed,
                       (SELECT coalesce(array_agg(seat_no ORDER BY seat_no), '{}') FROM locked
                        WHERE status <> 'available') AS unavailable
                """, (rs, i) -> new SeatConfirmation(rs.getInt("found"), rs.getInt("confirmed"),
                List.of((String[]) rs.getArray("unavailable").getArray())),
                showId, seats, reservationId, userId, showId);
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

    /**
     * Seat counts for shows created after {@code since}, with all three statuses (including
     * zero counts) and each show's declared total, from one snapshot. Bounding by age keeps the query cost and the number of
     * metric series from growing with every show ever created.
     */
    public List<SeatCount> countByShowAndStatus(Instant since) {
        return jdbc.query("""
                SELECT sh.id AS show_id, sh.total_seats, st.status, count(s.seat_no) AS seats
                FROM shows sh
                CROSS JOIN (VALUES ('available'), ('held'), ('confirmed')) AS st (status)
                LEFT JOIN seats s ON s.show_id = sh.id AND s.status = st.status
                WHERE sh.created_at >= ?
                GROUP BY sh.id, sh.total_seats, st.status
                """, (rs, i) -> new SeatCount(rs.getObject("show_id", UUID.class), rs.getInt("total_seats"),
                rs.getString("status"), rs.getLong("seats")), Timestamp.from(since));
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
