package com.assignment.tickets.observability;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.stereotype.Component;

/**
 * Readiness check: "can we reach PostgreSQL right now?", answered within ~3 seconds.
 * <p>
 * It uses its own single-connection pool instead of the application pool, for two reasons:
 * <ul>
 *   <li>During a burst the application pool is legitimately exhausted and requests queue for
 *       connections. Readiness must not report DOWN (and get the instance pulled from the load
 *       balancer) just because the pool is busy.</li>
 *   <li>The application pool waits up to 30s for a connection so bursts queue instead of failing.
 *       A probe sharing it could hang that long on an unreachable database; this one gives up fast.</li>
 * </ul>
 */
@Slf4j
@Component("database")
public class DatabaseHealthIndicator implements HealthIndicator {

    private static final int QUERY_TIMEOUT_SECONDS = 2;
    /** Probes arriving within this window share one database round trip. */
    private static final long CACHE_MILLIS = 1_000;

    private final HikariDataSource dataSource;
    /** ReentrantLock, not synchronized: a virtual thread blocking on JDBC inside synchronized pins its carrier. */
    private final ReentrantLock checkLock = new ReentrantLock();
    private volatile Snapshot last;

    private record Snapshot(Health health, long checkedAt) {
    }

    public DatabaseHealthIndicator(JdbcConnectionDetails connection) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("health");
        config.setJdbcUrl(connection.getJdbcUrl());
        config.setUsername(connection.getUsername());
        config.setPassword(connection.getPassword());
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(2_000);
        config.setValidationTimeout(1_000);
        config.setInitializationFailTimeout(-1); // start even if the database is down; report DOWN instead
        config.addDataSourceProperty("connectTimeout", "2");
        config.addDataSourceProperty("socketTimeout", "3");
        this.dataSource = new HikariDataSource(config);
    }

    /**
     * The pool has one connection, so concurrent probes would queue behind each other and, with
     * a remote database, time out and report DOWN while the database is fine. Instead, probes
     * that arrive together wait for one check and share its result for {@link #CACHE_MILLIS}.
     */
    @Override
    public Health health() {
        Health cached = freshResult();
        if (cached != null) {
            return cached;
        }
        checkLock.lock();
        try {
            cached = freshResult();
            if (cached != null) {
                return cached;
            }
            Health health = check();
            last = new Snapshot(health, System.currentTimeMillis());
            return health;
        } finally {
            checkLock.unlock();
        }
    }

    private Health freshResult() {
        Snapshot snapshot = last;
        return snapshot != null && System.currentTimeMillis() - snapshot.checkedAt() < CACHE_MILLIS
                ? snapshot.health() : null;
    }

    private Health check() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.execute("SELECT 1");
            return Health.up().build();
        } catch (SQLException e) {
            log.warn("Readiness database check failed: {}", e.toString());
            return Health.down().withDetail("error", e.getClass().getSimpleName()).build();
        }
    }

    @PreDestroy
    void close() {
        dataSource.close();
    }
}
