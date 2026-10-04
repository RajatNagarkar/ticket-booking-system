package com.assignment.tickets.dto.response;

import com.assignment.tickets.entity.Seat;
import com.assignment.tickets.entity.Show;
import java.util.List;
import java.util.UUID;

public record ShowResponse(
        UUID id,
        String name,
        long pricePaise,
        int perUserLimit,
        int totalSeats,
        Counts counts,
        List<SeatResponse> seats) {

    /** available + held + confirmed == total is the reconciliation invariant. */
    public record Counts(int available, int held, int confirmed, int total) {
    }

    public static ShowResponse from(Show show, List<Seat> seats) {
        int available = 0, held = 0, confirmed = 0;
        for (Seat seat : seats) {
            switch (seat.status()) {
                case Seat.AVAILABLE -> available++;
                case Seat.HELD -> held++;
                case Seat.CONFIRMED -> confirmed++;
                default -> throw new IllegalStateException("Unknown seat status: " + seat.status());
            }
        }
        return new ShowResponse(show.id(), show.name(), show.pricePaise(), show.perUserLimit(),
                show.totalSeats(), new Counts(available, held, confirmed, seats.size()),
                seats.stream().map(SeatResponse::from).toList());
    }
}
