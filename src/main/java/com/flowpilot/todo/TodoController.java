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
                  @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return TodoPage.of(service.list(jwt.getSubject(), status, dueFrom, dueTo, page, size));
    }

    @PatchMapping("/{id}")
    ResponseEntity<TodoResponse> patch(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestHeader(value="If-Match", required=false) String match, @RequestBody JsonNode body) {
        TodoPatch patch = TodoPatch.from(body);
        Todo todo = service.patch(jwt.getSubject(), id, version(match), patch);
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
        return Long.parseLong(value.substring(1, value.length()-1));
    }
    private static String etag(long version) { return "\"" + version + "\""; }
}

record TodoCreate(@NotBlank @Size(max=200) String title, @Size(max=5000) String description,
                  TodoStatus status, LocalDate dueDate) {}
record TodoPatch(String title, boolean descriptionPresent, String description, TodoStatus status,
                 boolean dueDatePresent, LocalDate dueDate) {
    static TodoPatch from(JsonNode node) {
        String title = text(node, "title");
        if (node.has("title") && (title == null || title.isBlank() || title.length() > 200)) throw new BadRequest("title is invalid");
        String description = text(node, "description");
        if (description != null && description.length() > 5000) throw new BadRequest("description is too long");
        TodoStatus status = node.hasNonNull("status") ? parseStatus(node.get("status").asText()) : null;
        LocalDate due = node.hasNonNull("dueDate") ? parseDate(node.get("dueDate").asText()) : null;
        return new TodoPatch(title, node.has("description"), description, status, node.has("dueDate"), due);
    }
    private static String text(JsonNode n, String f) { return n.hasNonNull(f) && n.get(f).isTextual() ? n.get(f).asText() : null; }
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
