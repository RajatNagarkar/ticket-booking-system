package com.assignment.tickets.observability;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
@Component("database")
public class DatabaseHealthIndicator implements HealthIndicator {

    private static final int QUERY_TIMEOUT_SECONDS = 2;

    private final HikariDataSource dataSource;

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

    @Override
    public Health health() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            statement.execute("SELECT 1");
            return Health.up().build();
        } catch (SQLException e) {
            return Health.down().withDetail("error", e.getClass().getSimpleName()).build();
        }
    }

    @PreDestroy
    void close() {
        dataSource.close();
    }
}
