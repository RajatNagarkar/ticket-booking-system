package com.assignment.tickets.service;

import com.assignment.tickets.entity.Reservation;

/**
 * Internal signal, never sent to clients: the user already holds a confirmed reservation for
 * exactly the requested seats. Thrown inside the transaction so the new attempt rolls back
 * (no key claimed, no quota used); the service then returns the existing reservation.
 */
class SameSeatsAlreadyReservedException extends RuntimeException {

    private final transient Reservation existing;

    SameSeatsAlreadyReservedException(Reservation existing) {
        super(null, null, false, false);
        this.existing = existing;
    }

    Reservation existing() {
        return existing;
    }
}
