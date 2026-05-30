package com.example.copilot.tools;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.example.copilot.tasks.TaskService;
import com.example.copilot.tasks.TaskService.TaskHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class TaskTool {

    private static final Logger log = LoggerFactory.getLogger(TaskTool.class);

    private final TaskService taskService;

    public TaskTool(TaskService taskService) {
        this.taskService = taskService;
    }

    public record CreatedTask(UUID id, String title, String status, OffsetDateTime createdAt) {}

    @Tool(description = """
            Create a follow-up task / action item and persist it to the tasks
            table. Call this ONLY when the user explicitly asks to create,
            track, schedule, or log a task, follow-up, action item, or to-do.
            Never create a task speculatively, never as a side effect of
            answering an unrelated question, and never just to "summarize"
            what you did. The task is written to the database; this is a
            side-effect tool, not a read tool. Returns a handle with the
            database-generated id, the title as stored, the status, and the
            createdAt timestamp.
            """)
    public CreatedTask createTask(
            @ToolParam(description = "task title, e.g. \"Review ABC Corp renewal proposal\"")
            String title,
            @ToolParam(required = false, description = "longer description / notes")
            String description,
            @ToolParam(required = false, description = "customer or account name, e.g. \"ABC Corp\"")
            String customerName,
            @ToolParam(required = false, description = "due date in ISO format yyyy-MM-dd, e.g. \"2026-07-15\"")
            String dueDate,
            @ToolParam(required = false, description = "priority: one of LOW, MEDIUM, HIGH (default MEDIUM)")
            String priority
    ) {
        log.info("[tool:task] called  title=\"{}\" description.len={} customer=\"{}\" dueDate={} priority={}",
                title,
                description == null ? 0 : description.length(),
                customerName,
                dueDate,
                priority);

        try {
            TaskHandle h = taskService.create(title, description, customerName, dueDate, priority);
            CreatedTask result = new CreatedTask(h.id(), h.title(), h.status(), h.createdAt());
            log.info("[tool:task] result  id={} title=\"{}\" status={} createdAt={}",
                    result.id(), result.title(), result.status(), result.createdAt());
            return result;
        } catch (RuntimeException e) {
            // Spring AI's MethodToolCallback converts thrown exceptions to a
            // tool-error message returned to the model, masking the cause from
            // server logs. Log first, then rethrow so the @Transactional on
            // TaskService rolls back.
            log.error("[tool:task] failed  title=\"{}\" customer=\"{}\"", title, customerName, e);
            throw e;
        }
    }
}
