package com.assignment.tickets.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.assignment.tickets.IntegrationTest;
import com.assignment.tickets.exception.SeatTakenException;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class TransactionRetryTest extends IntegrationTest {

    @Autowired
    TransactionRetry retry;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry registry;

    @Test
    void deadlockIsRetriedInAFreshTransaction() {
        String marker = "retry-" + UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();
        double retriesBefore = retries("40P01");

        String result = retry.inTransaction(() -> {
            insertShow(marker);
            if (attempts.incrementAndGet() == 1) {
                throw new CannotAcquireLockException("simulated", new SQLException("deadlock detected", "40P01"));
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(2);
        // The first attempt's insert was rolled back; only the successful attempt's row exists.
        assertThat(showsNamed(marker)).isEqualTo(1);
        assertThat(retries("40P01") - retriesBefore).isEqualTo(1);
    }

    @Test
    void serializationFailureIsRetried() {
        AtomicInteger attempts = new AtomicInteger();

        retry.inTransaction(() -> {
            if (attempts.incrementAndGet() == 1) {
                throw new CannotAcquireLockException("simulated", new SQLException("could not serialize", "40001"));
            }
            return null;
        });

        assertThat(attempts).hasValue(2);
    }

    @Test
    void givesUpAfterThreeAttempts() {
        String marker = "retry-" + UUID.randomUUID();
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> retry.inTransaction(() -> {
            insertShow(marker);
            attempts.incrementAndGet();
            throw new CannotAcquireLockException("simulated", new SQLException("deadlock detected", "40P01"));
        })).isInstanceOf(CannotAcquireLockException.class);

        assertThat(attempts).hasValue(TransactionRetry.MAX_ATTEMPTS);
        assertThat(showsNamed(marker)).isZero();
    }

    @Test
    void domainDeclinesAndOtherErrorsAreNotRetried() {
        AtomicInteger declines = new AtomicInteger();
        assertThatThrownBy(() -> retry.inTransaction(() -> {
            declines.incrementAndGet();
            throw new SeatTakenException(List.of("A1"));
        })).isInstanceOf(SeatTakenException.class);
        assertThat(declines).hasValue(1);

        AtomicInteger constraintErrors = new AtomicInteger();
        assertThatThrownBy(() -> retry.inTransaction(() -> {
            constraintErrors.incrementAndGet();
            throw new DataIntegrityViolationException("dup", new SQLException("duplicate key", "23505"));
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(constraintErrors).hasValue(1);
    }

    @Test
    void realPostgresDeadlockIsResolvedByRetry() throws Exception {
        UUID rowA = insertShow("lock-a-" + UUID.randomUUID());
        UUID rowB = insertShow("lock-b-" + UUID.randomUUID());
        CountDownLatch bothHoldFirstLock = new CountDownLatch(2);
        double retriesBefore = retries("40P01");

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            // Opposite lock order: each waits for the row the other holds, so Postgres must abort one.
            Future<String> first = pool.submit(() -> lockBoth(rowA, rowB, bothHoldFirstLock, "first"));
            Future<String> second = pool.submit(() -> lockBoth(rowB, rowA, bothHoldFirstLock, "second"));

            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo("first");
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo("second");
        }
        assertThat(retries("40P01") - retriesBefore).isGreaterThanOrEqualTo(1);
    }

    private String lockBoth(UUID firstRow, UUID secondRow, CountDownLatch bothHoldFirstLock, String name) {
        AtomicInteger attempts = new AtomicInteger();
        return retry.inTransaction(() -> {
            lockShow(firstRow);
            if (attempts.incrementAndGet() == 1) {
                bothHoldFirstLock.countDown();
                await(bothHoldFirstLock);
            }
            lockShow(secondRow);
            return name;
        });
    }

    private void lockShow(UUID id) {
        jdbc.queryForObject("SELECT id FROM shows WHERE id = ? FOR UPDATE", UUID.class, id);
    }

    private UUID insertShow(String name) {
        // With its one seat row, so the show satisfies the invariant the seat gauges check.
        return jdbc.queryForObject("""
                WITH show AS (
                    INSERT INTO shows (name, price_paise, total_seats) VALUES (?, 100, 1) RETURNING id
                ), seat AS (
                    INSERT INTO seats (show_id, seat_no) SELECT id, 'A1' FROM show
                )
                SELECT id FROM show
                """, UUID.class, name);
    }

    private int showsNamed(String name) {
        return jdbc.queryForObject("SELECT count(*) FROM shows WHERE name = ?", Integer.class, name);
    }

    private double retries(String sqlState) {
        var counter = registry.find("db.transaction.retries").tag("sqlstate", sqlState).counter();
        return counter == null ? 0 : counter.count();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Other transaction never took its first lock");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
