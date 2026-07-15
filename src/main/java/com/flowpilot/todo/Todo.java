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
        this.title = title.trim();
        this.description = description;
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
}
enum TodoStatus { TODO, IN_PROGRESS, DONE }
