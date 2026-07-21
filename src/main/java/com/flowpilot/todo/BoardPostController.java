package com.flowpilot.todo;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class BoardPostController {
    private final BoardPostService service;

    BoardPostController(BoardPostService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/boards/{boardId}/posts")
    BoardPostPage list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID boardId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return BoardPostPage.of(service.list(boardId, page, size, sort), jwt.getSubject());
    }

    @PostMapping("/api/v1/boards/{boardId}/posts")
    ResponseEntity<BoardPostResponse> create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID boardId,
            @Valid @RequestBody BoardPostCreate request) {
        BoardPost post = service.create(jwt.getSubject(), boardId, request);
        return ResponseEntity.created(URI.create("/api/v1/posts/" + post.id))
            .eTag(ETags.format(post.version)).body(BoardPostResponse.of(post, jwt.getSubject()));
    }

    @GetMapping("/api/v1/posts/{postId}")
    ResponseEntity<BoardPostResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId) {
        BoardPost post = service.get(postId);
        return ResponseEntity.ok().eTag(ETags.format(post.version))
            .body(BoardPostResponse.of(post, jwt.getSubject()));
    }

    @PatchMapping("/api/v1/posts/{postId}")
    ResponseEntity<BoardPostResponse> patch(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId,
            @RequestHeader(value = "If-Match", required = false) String match,
            @RequestBody JsonNode body) {
        BoardPost post = service.patch(jwt.getSubject(), postId, ETags.parse(match), BoardPostPatch.from(body));
        return ResponseEntity.ok().eTag(ETags.format(post.version))
            .body(BoardPostResponse.of(post, jwt.getSubject()));
    }

    @DeleteMapping("/api/v1/posts/{postId}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID postId,
            @RequestHeader(value = "If-Match", required = false) String match) {
        service.delete(jwt.getSubject(), postId, ETags.parse(match));
        return ResponseEntity.noContent().build();
    }
}

record BoardPostCreate(@NotBlank @Size(max = 200) String title, @NotNull EditorType editorType,
                       @NotBlank @Size(max = 100_000) String content) {
    BoardPostCreate {
        if (title != null) title = title.trim();
    }
}

record BoardPostPatch(String title, String content) {
    private static final Set<String> FIELDS = Set.of("title", "content");

    static BoardPostPatch from(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw new BadRequest("post patch must be a non-empty object");
        }
        node.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) throw new BadRequest("unknown post patch field");
        });

        String title = null;
        if (node.has("title")) {
            if (!node.get("title").isTextual()) throw new BadRequest("title must be a string");
            title = node.get("title").textValue().trim();
            if (title.isEmpty() || title.length() > 200) throw new BadRequest("title is invalid");
        }

        String content = null;
        if (node.has("content")) {
            if (!node.get("content").isTextual()) throw new BadRequest("content must be a string");
            content = node.get("content").textValue();
            if (content.isBlank() || content.length() > 100_000) throw new BadRequest("content is invalid");
        }
        return new BoardPostPatch(title, content);
    }
}

record BoardPostSummary(UUID id, UUID boardId, String title, EditorType editorType,
                        Instant createdAt, Instant updatedAt, long version, boolean isAuthor) {
    static BoardPostSummary of(BoardPost post, String subject) {
        return new BoardPostSummary(post.id, post.board.id, post.title, post.editorType,
            post.createdAt, post.updatedAt, post.version, post.isAuthor(subject));
    }
}

record BoardPostResponse(UUID id, UUID boardId, String title, EditorType editorType, String content,
                         Instant createdAt, Instant updatedAt, long version, boolean isAuthor) {
    static BoardPostResponse of(BoardPost post, String subject) {
        return new BoardPostResponse(post.id, post.board.id, post.title, post.editorType, post.content,
            post.createdAt, post.updatedAt, post.version, post.isAuthor(subject));
    }
}

record BoardPostPage(List<BoardPostSummary> items, int page, int size, long totalElements, int totalPages) {
    static BoardPostPage of(Page<BoardPost> page, String subject) {
        return new BoardPostPage(page.map(post -> BoardPostSummary.of(post, subject)).getContent(),
            page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }
}
