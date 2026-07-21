package com.flowpilot.todo;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "board_posts")
class BoardPost {
    @Id UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "board_id", nullable = false) Board board;
    @Column(name = "author_id", nullable = false, length = 255) String authorId;
    @Column(nullable = false, length = 200) String title;
    @Enumerated(EnumType.STRING)
    @Column(name = "editor_type", nullable = false, length = 20) EditorType editorType;
    @Column(nullable = false, length = 100_000) String content;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;
    @Column(name = "deleted_at") Instant deletedAt;
    @Column(name = "deleted_by", length = 255) String deletedBy;
    @Version long version;

    protected BoardPost() {}

    BoardPost(Board board, String authorId, String title, EditorType editorType, String content) {
        this.id = UUID.randomUUID();
        this.board = java.util.Objects.requireNonNull(board);
        this.authorId = validAuthor(authorId);
        this.title = validTitle(title);
        this.editorType = java.util.Objects.requireNonNull(editorType);
        this.content = validContent(content);
        this.createdAt = this.updatedAt = Instant.now();
    }

    void patch(BoardPostPatch patch) {
        if (patch.title() != null) title = validTitle(patch.title());
        if (patch.content() != null) content = validContent(patch.content());
        updatedAt = Instant.now();
    }

    void delete(String actor) {
        if (deletedAt != null) throw new BoardPostNotFound();
        deletedAt = Instant.now();
        deletedBy = validAuthor(actor);
        updatedAt = deletedAt;
    }

    boolean isAuthor(String subject) {
        return authorId.equals(subject);
    }

    private static String validTitle(String value) {
        if (value == null) throw new BadRequest("post title is required");
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > 200) throw new BadRequest("post title is invalid");
        return trimmed;
    }

    private static String validContent(String value) {
        if (value == null || value.isBlank() || value.length() > 100_000) {
            throw new BadRequest("post content is invalid");
        }
        return value;
    }

    private static String validAuthor(String value) {
        if (value == null || value.isBlank() || value.length() > 255) throw new BadRequest("author is invalid");
        return value;
    }
}

enum EditorType { MARKDOWN, RICH_TEXT }
