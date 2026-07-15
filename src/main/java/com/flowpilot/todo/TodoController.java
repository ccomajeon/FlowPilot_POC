package com.flowpilot.todo;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/todos")
class TodoController {
    private final TodoService service;
    TodoController(TodoService service) { this.service = service; }

    @PostMapping
    ResponseEntity<TodoResponse> create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody TodoCreate request) {
        Todo todo = service.create(jwt.getSubject(), request);
        return ResponseEntity.created(URI.create("/api/v1/todos/" + todo.id)).eTag(etag(todo.version)).body(TodoResponse.of(todo));
    }

    @GetMapping("/{id}")
    ResponseEntity<TodoResponse> get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        Todo todo = service.get(jwt.getSubject(), id);
        return ResponseEntity.ok().eTag(etag(todo.version)).body(TodoResponse.of(todo));
    }

    @GetMapping
    TodoPage list(@AuthenticationPrincipal Jwt jwt, @RequestParam(required=false) TodoStatus status,
                  @RequestParam(required=false) LocalDate dueFrom, @RequestParam(required=false) LocalDate dueTo,
                  @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
                  @RequestParam(defaultValue="createdAt,desc") String sort) {
        return TodoPage.of(service.list(jwt.getSubject(), status, dueFrom, dueTo, page, size, sort));
    }

    @PatchMapping("/{id}")
    ResponseEntity<TodoResponse> patch(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestHeader(value="If-Match", required=false) String match, @RequestBody JsonNode body) {
        long expected = version(match);
        TodoPatch patch = TodoPatch.from(body);
        Todo todo = service.patch(jwt.getSubject(), id, expected, patch);
        return ResponseEntity.ok().eTag(etag(todo.version)).body(TodoResponse.of(todo));
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestHeader(value="If-Match", required=false) String match) {
        service.delete(jwt.getSubject(), id, version(match));
        return ResponseEntity.noContent().build();
    }

    private static long version(String value) {
        if (value == null) throw new PreconditionRequired();
        if (!value.matches("\"[0-9]+\"")) throw new BadRequest("If-Match must be a quoted version");
        try {
            return Long.parseLong(value.substring(1, value.length()-1));
        } catch (NumberFormatException e) {
            throw new BadRequest("If-Match version is out of range");
        }
    }
    private static String etag(long version) { return "\"" + version + "\""; }
}

record TodoCreate(@NotBlank @Size(max=200) String title, @Size(max=5000) String description,
                  TodoStatus status, LocalDate dueDate) {
    TodoCreate {
        if (title != null) {
            title = title.trim();
        }
    }
}
record TodoPatch(String title, boolean descriptionPresent, String description, TodoStatus status,
                 boolean dueDatePresent, LocalDate dueDate) {
    private static final Set<String> FIELDS = Set.of("title", "description", "status", "dueDate");

    static TodoPatch from(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) throw new BadRequest("patch must be a non-empty object");
        node.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) throw new BadRequest("unknown patch field");
        });

        String title = null;
        if (node.has("title")) {
            if (!node.get("title").isTextual()) throw new BadRequest("title must be a string");
            title = node.get("title").textValue();
            if (title.trim().isEmpty() || title.trim().length() > 200) throw new BadRequest("title is invalid");
        }

        String description = null;
        if (node.has("description")) {
            if (!node.get("description").isNull() && !node.get("description").isTextual())
                throw new BadRequest("description must be a string or null");
            description = node.get("description").isNull() ? null : node.get("description").textValue();
        }
        if (description != null && description.length() > 5000) throw new BadRequest("description is too long");

        TodoStatus status = null;
        if (node.has("status")) {
            if (!node.get("status").isTextual()) throw new BadRequest("status must be a string");
            status = parseStatus(node.get("status").textValue());
        }

        LocalDate due = null;
        if (node.has("dueDate") && !node.get("dueDate").isNull()) {
            if (!node.get("dueDate").isTextual()) throw new BadRequest("dueDate must be a string or null");
            due = parseDate(node.get("dueDate").textValue());
        }
        return new TodoPatch(title, node.has("description"), description, status, node.has("dueDate"), due);
    }
    private static TodoStatus parseStatus(String v) { try { return TodoStatus.valueOf(v); } catch (Exception e) { throw new BadRequest("status is invalid"); } }
    private static LocalDate parseDate(String v) { try { return LocalDate.parse(v); } catch (Exception e) { throw new BadRequest("dueDate is invalid"); } }
}
record TodoResponse(UUID id, String title, String description, TodoStatus status, LocalDate dueDate,
                    Instant createdAt, Instant updatedAt, long version) {
    static TodoResponse of(Todo t) { return new TodoResponse(t.id,t.title,t.description,t.status,t.dueDate,t.createdAt,t.updatedAt,t.version); }
}
record TodoPage(List<TodoResponse> items, int page, int size, long totalElements, int totalPages) {
    static TodoPage of(Page<Todo> p) { return new TodoPage(p.map(TodoResponse::of).getContent(),p.getNumber(),p.getSize(),p.getTotalElements(),p.getTotalPages()); }
}
