package com.assignment.tickets.observability;

import org.slf4j.MDC;

/**
 * MDC keys attached to every log line of a request and to its access-log line. Each request
 * runs on one (virtual) thread, and {@link RequestLoggingFilter} clears them when it ends.
 */
public final class RequestContext {

    public static final String REQUEST_ID = "request_id";
    public static final String USER_ID = "user_id";
    public static final String SHOW_ID = "show_id";
    public static final String RESERVATION_ID = "reservation_id";
    /** {@code confirmed}, {@code idempotent_replay}, {@code cancelled}, or the error code. */
    public static final String OUTCOME = "outcome";

    private RequestContext() {
    }

    public static void put(String key, Object value) {
        MDC.put(key, String.valueOf(value));
    }
}
