package com.example.copilot.tasks;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final String DEFAULT_PRIORITY = "MEDIUM";

    private final TaskRepository repository;

    public TaskService(TaskRepository repository) {
        this.repository = repository;
    }

    public record TaskHandle(UUID id, String title, String status, OffsetDateTime createdAt) {}

    @Transactional
    public TaskHandle create(String title, String description, String customer,
                             String dueDateIso, String priority) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        String trimmedTitle = title.strip();
        String normalizedPriority = normalizePriority(priority);
        LocalDate parsedDueDate = parseDueDate(dueDateIso);

        TaskRepository.Inserted inserted = repository.insert(
                trimmedTitle,
                blankToNull(description),
                blankToNull(customer),
                parsedDueDate,
                normalizedPriority
        );
        return new TaskHandle(inserted.id(), trimmedTitle, inserted.status(), inserted.createdAt());
    }

    private static String normalizePriority(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_PRIORITY;
        }
        String upper = raw.strip().toUpperCase(Locale.ROOT);
        if (PRIORITIES.contains(upper)) {
            return upper;
        }
        log.info("[tool:task] model passed invalid priority '{}', defaulting to {}", raw, DEFAULT_PRIORITY);
        return DEFAULT_PRIORITY;
    }

    private static LocalDate parseDueDate(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(iso.strip());
        } catch (DateTimeParseException e) {
            log.info("[tool:task] model passed unparseable dueDate '{}', storing null", iso);
            return null;
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.strip();
    }
}
