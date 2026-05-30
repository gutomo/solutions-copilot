package com.example.copilot.tasks;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TaskRepository {

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Inserted(UUID id, String status, OffsetDateTime createdAt) {}

    public Inserted insert(String title, String description, String customer,
                           LocalDate dueDate, String priority) {
        String sql = """
                INSERT INTO tasks (title, description, customer, due_date, priority)
                VALUES (?, ?, ?, ?, ?)
                RETURNING id, status, created_at
                """;
        return jdbc.queryForObject(sql, (rs, n) -> new Inserted(
                rs.getObject("id", UUID.class),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class)
        ), title, description, customer, dueDate, priority);
    }
}
