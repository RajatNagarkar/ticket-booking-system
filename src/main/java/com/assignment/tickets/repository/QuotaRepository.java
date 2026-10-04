package com.assignment.tickets.repository;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class QuotaRepository {

    private final JdbcTemplate jdbc;

    /**
     * Atomically adds {@code seats} to the user's count for the show, only if the new total
     * stays within {@code limit}. One statement: the first reservation inserts the row; later
     * ones take the row lock and re-check the guard on the latest committed count, so parallel
     * requests from the same user serialize here and can never overshoot.
     * <p>
     * Callers must ensure {@code seats <= limit}; the insert path does not re-check it.
     *
     * @return true if the seats were added, false if the limit would be exceeded
     */
    public boolean tryAcquire(UUID showId, String userId, int seats, int limit) {
        return jdbc.update("""
                INSERT INTO user_show_quota AS q (show_id, user_id, held)
                VALUES (?, ?, ?)
                ON CONFLICT (show_id, user_id)
                DO UPDATE SET held = q.held + EXCLUDED.held
                WHERE q.held + EXCLUDED.held <= ?
                """, showId, userId, seats, limit) == 1;
    }
}
