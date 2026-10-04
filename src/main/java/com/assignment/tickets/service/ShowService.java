package com.assignment.tickets.service;

import com.assignment.tickets.dto.request.CreateShowRequest;
import com.assignment.tickets.dto.response.ShowResponse;
import com.assignment.tickets.entity.Show;
import com.assignment.tickets.exception.BadRequestException;
import com.assignment.tickets.exception.ShowNotFoundException;
import com.assignment.tickets.repository.SeatRepository;
import com.assignment.tickets.repository.ShowRepository;
import java.util.HashSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShowService {

    static final int DEFAULT_PER_USER_LIMIT = 4;

    private final ShowRepository shows;
    private final SeatRepository seats;

    @Transactional
    public ShowResponse create(CreateShowRequest request) {
        if (new HashSet<>(request.seats()).size() != request.seats().size()) {
            throw new BadRequestException("duplicate_seats", "Seat numbers must be unique");
        }
        int perUserLimit = request.perUserLimit() != null ? request.perUserLimit() : DEFAULT_PER_USER_LIMIT;
        UUID id = shows.insert(request.name(), request.pricePaise(), perUserLimit, request.seats().size());
        seats.insertAll(id, request.seats());
        return get(id);
    }

    @Transactional(readOnly = true)
    public ShowResponse get(UUID id) {
        Show show = shows.findById(id).orElseThrow(() -> new ShowNotFoundException(id));
        return ShowResponse.from(show, seats.findByShow(id));
    }
}
