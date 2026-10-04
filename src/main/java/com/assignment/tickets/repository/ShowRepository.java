package com.assignment.tickets.repository;

import com.assignment.tickets.entity.Show;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class ShowRepository {

    private static final RowMapper<Show> SHOW_MAPPER = (rs, i) -> new Show(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getLong("price_paise"),
            rs.getInt("per_user_limit"),
            rs.getInt("total_seats"));

    private final JdbcTemplate jdbc;

    public UUID insert(String name, long pricePaise, int perUserLimit, int totalSeats) {
        return jdbc.queryForObject("""
                INSERT INTO shows (name, price_paise, per_user_limit, total_seats)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """, UUID.class, name, pricePaise, perUserLimit, totalSeats);
    }

    public Optional<Show> findById(UUID id) {
        return jdbc.query("""
                SELECT id, name, price_paise, per_user_limit, total_seats
                FROM shows
                WHERE id = ?
                """, SHOW_MAPPER, id).stream().findFirst();
    }
}
