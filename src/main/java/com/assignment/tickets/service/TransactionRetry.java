package com.assignment.tickets.service;

import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs work in its own transaction and retries it when PostgreSQL aborts the transaction for
 * a transient concurrency reason: deadlock (40P01) or serialization failure (40001).
 * <p>
 * Each attempt is a brand-new transaction: the failed one has already been rolled back, so a
 * retry never sees its partial writes. Sorted lock ordering should make deadlocks impossible;
 * this is the safety net that keeps a rare one from becoming a 500. Domain declines and any
 * other error are never retried.
 */
@Slf4j
@Component
public class TransactionRetry {

    static final int MAX_ATTEMPTS = 3;
    private static final Set<String> RETRYABLE_SQL_STATES = Set.of("40P01", "40001");

    private final TransactionTemplate transaction;
    private final MeterRegistry registry;

    public TransactionRetry(PlatformTransactionManager transactionManager, MeterRegistry registry) {
        this.transaction = new TransactionTemplate(transactionManager);
        this.registry = registry;
    }

    public <T> T inTransaction(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return transaction.execute(status -> work.get());
            } catch (RuntimeException e) {
                String sqlState = retryableSqlState(e);
                if (sqlState == null || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                registry.counter("db.transaction.retries", "sqlstate", sqlState).increment();
                log.warn("Transaction aborted with SQLSTATE {} (attempt {}/{}), retrying", sqlState, attempt, MAX_ATTEMPTS);
                backOff(attempt);
            }
        }
    }

    /** The SQLSTATE if any cause is a retryable SQLException, otherwise null. */
    static String retryableSqlState(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && RETRYABLE_SQL_STATES.contains(sql.getSQLState())) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /** Short, jittered pause so the transactions that collided don't collide again in lockstep. */
    private static void backOff(int attempt) {
        try {
            Thread.sleep(ThreadLocalRandom.current().nextLong(5, 20) * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while retrying a transaction", e);
        }
    }
}
