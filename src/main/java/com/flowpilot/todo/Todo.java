package com.flowpilot.todo;

import jakarta.persistence.*;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name = "todos")
class Todo {
    @Id UUID id;
    @Column(name="owner_id", nullable=false, length=255) String ownerId;
    @Column(nullable=false, length=200) String title;
    @Column(length=5000) String description;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=20) TodoStatus status;
    @Column(name="due_date") LocalDate dueDate;
    @Column(name="created_at", nullable=false) Instant createdAt;
    @Column(name="updated_at", nullable=false) Instant updatedAt;
    @Version long version;

    protected Todo() {}
    Todo(String ownerId, String title, String description, TodoStatus status, LocalDate dueDate) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        this.title = validTitle(title);
        this.description = validDescription(description);
        this.status = status == null ? TodoStatus.TODO : status;
        this.dueDate = dueDate;
        this.createdAt = this.updatedAt = Instant.now();
    }
    void patch(TodoPatch p) {
        if (p.title() != null) title = p.title().trim();
        if (p.descriptionPresent()) description = p.description();
        if (p.status() != null) status = p.status();
        if (p.dueDatePresent()) dueDate = p.dueDate();
        updatedAt = Instant.now();
    }

    private static String validTitle(String value) {
        if (value == null) throw new BadRequest("title is required");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 200) {
            throw new BadRequest("title is invalid");
        }
        return trimmed;
    }

    private static String validDescription(String value) {
        if (value != null && value.length() > 5000) {
            throw new BadRequest("description is too long");
        }
        return value;
    }
}
enum TodoStatus { TODO, IN_PROGRESS, DONE }
