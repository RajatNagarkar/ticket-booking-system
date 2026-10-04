package com.assignment.tickets.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;

class DatabaseHealthIndicatorTest {

    @Test
    void unreachableDatabaseIsReportedDownQuickly() {
        JdbcConnectionDetails unreachable = new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return "x";
            }

            @Override
            public String getPassword() {
                return "x";
            }

            @Override
            public String getJdbcUrl() {
                return "jdbc:postgresql://localhost:1/none";
            }
        };
        DatabaseHealthIndicator indicator = new DatabaseHealthIndicator(unreachable);
        try {
            long start = System.nanoTime();
            Health health = indicator.health();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(elapsedMs).isLessThan(5_000);
        } finally {
            indicator.close();
        }
    }
}
