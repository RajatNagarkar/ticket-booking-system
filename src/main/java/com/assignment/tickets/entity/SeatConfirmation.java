package com.assignment.tickets.entity;

import java.util.List;

/**
 * Outcome of locking and confirming a reservation's seats in one statement.
 *
 * @param found       requested seats that exist in the show
 * @param confirmed   seats this reservation took
 * @param unavailable seats that were already held or confirmed once their lock was acquired
 */
public record SeatConfirmation(int found, int confirmed, List<String> unavailable) {
}
