package com.flowpilot.todo;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "boards")
class Board {
    @Id UUID id;
    @Column(nullable = false, unique = true, length = 100) String name;
    @Column(length = 1000) String description;
    @Column(name = "display_order", nullable = false) int displayOrder;
    @Column(nullable = false) boolean active;
    @Column(name = "created_by", nullable = false, length = 255) String createdBy;
    @Column(name = "updated_by", nullable = false, length = 255) String updatedBy;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Version long version;

    protected Board() {}

    Board(String actor, String name, String description, int displayOrder, boolean active) {
        this.id = UUID.randomUUID();
        this.name = validName(name);
        this.description = validDescription(description);
        this.displayOrder = validDisplayOrder(displayOrder);
        this.active = active;
        this.createdBy = this.updatedBy = validActor(actor);
        this.createdAt = this.updatedAt = Instant.now();
    }

    void patch(String actor, BoardPatch patch) {
        if (patch.name() != null) name = validName(patch.name());
        if (patch.descriptionPresent()) description = validDescription(patch.description());
        if (patch.displayOrder() != null) displayOrder = validDisplayOrder(patch.displayOrder());
        if (patch.active() != null) active = patch.active();
        updatedBy = validActor(actor);
        updatedAt = Instant.now();
    }

    private static String validName(String value) {
        if (value == null) throw new BadRequest("board name is required");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 100) throw new BadRequest("board name is invalid");
        return trimmed;
    }

    private static String validDescription(String value) {
        if (value != null && value.length() > 1000) throw new BadRequest("board description is too long");
        return value;
    }

    private static int validDisplayOrder(int value) {
        if (value < 0 || value > 1_000_000) throw new BadRequest("displayOrder is invalid");
        return value;
    }

    private static String validActor(String value) {
        if (value == null || value.isBlank() || value.length() > 255) throw new BadRequest("actor is invalid");
        return value;
    }
}
