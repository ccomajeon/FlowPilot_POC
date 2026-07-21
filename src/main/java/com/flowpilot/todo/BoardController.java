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
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/boards")
class BoardController {
    private final BoardService service;

    BoardController(BoardService service) {
        this.service = service;
    }

    @GetMapping
    BoardPage list(Authentication authentication,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return BoardPage.of(service.list(includeInactive, isAdmin(authentication), page, size));
    }

    @GetMapping("/{id}")
    ResponseEntity<BoardResponse> get(Authentication authentication, @PathVariable UUID id) {
        Board board = service.get(id, isAdmin(authentication));
        return ResponseEntity.ok().eTag(ETags.format(board.version)).body(BoardResponse.of(board));
    }

    @PostMapping
    ResponseEntity<BoardResponse> create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody BoardCreate request) {
        Board board = service.create(jwt.getSubject(), request);
        return ResponseEntity.created(URI.create("/api/v1/boards/" + board.id))
            .eTag(ETags.format(board.version)).body(BoardResponse.of(board));
    }

    @PatchMapping("/{id}")
    ResponseEntity<BoardResponse> patch(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String match,
            @RequestBody JsonNode body) {
        Board board = service.patch(jwt.getSubject(), id, ETags.parse(match), BoardPatch.from(body));
        return ResponseEntity.ok().eTag(ETags.format(board.version)).body(BoardResponse.of(board));
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
            .anyMatch(authority -> "SCOPE_boards.admin".equals(authority.getAuthority()));
    }
}

record BoardCreate(@NotBlank @Size(max = 100) String name, @Size(max = 1000) String description,
                   @NotNull @Min(0) @Max(1_000_000) Integer displayOrder, Boolean active) {
    BoardCreate {
        if (name != null) name = name.trim();
    }
}

record BoardPatch(String name, boolean descriptionPresent, String description,
                  Integer displayOrder, Boolean active) {
    private static final Set<String> FIELDS = Set.of("name", "description", "displayOrder", "active");

    static BoardPatch from(JsonNode node) {
        if (node == null || !node.isObject() || node.isEmpty()) {
            throw new BadRequest("board patch must be a non-empty object");
        }
        node.fieldNames().forEachRemaining(field -> {
            if (!FIELDS.contains(field)) throw new BadRequest("unknown board patch field");
        });

        String name = null;
        if (node.has("name")) {
            if (!node.get("name").isTextual()) throw new BadRequest("name must be a string");
            name = node.get("name").textValue().trim();
            if (name.isEmpty() || name.length() > 100) throw new BadRequest("name is invalid");
        }

        String description = null;
        if (node.has("description")) {
            if (!node.get("description").isNull() && !node.get("description").isTextual()) {
                throw new BadRequest("description must be a string or null");
            }
            description = node.get("description").isNull() ? null : node.get("description").textValue();
            if (description != null && description.length() > 1000) {
                throw new BadRequest("description is too long");
            }
        }

        Integer displayOrder = null;
        if (node.has("displayOrder")) {
            JsonNode value = node.get("displayOrder");
            if (!value.isIntegralNumber() || !value.canConvertToInt()) {
                throw new BadRequest("displayOrder must be an integer");
            }
            displayOrder = value.intValue();
            if (displayOrder < 0 || displayOrder > 1_000_000) {
                throw new BadRequest("displayOrder is invalid");
            }
        }

        Boolean active = null;
        if (node.has("active")) {
            if (!node.get("active").isBoolean()) throw new BadRequest("active must be a boolean");
            active = node.get("active").booleanValue();
        }
        return new BoardPatch(name, node.has("description"), description, displayOrder, active);
    }
}

record BoardResponse(UUID id, String name, String description, int displayOrder, boolean active,
                     Instant createdAt, Instant updatedAt, long version) {
    static BoardResponse of(Board board) {
        return new BoardResponse(board.id, board.name, board.description, board.displayOrder, board.active,
            board.createdAt, board.updatedAt, board.version);
    }
}

record BoardPage(List<BoardResponse> items, int page, int size, long totalElements, int totalPages) {
    static BoardPage of(Page<Board> page) {
        return new BoardPage(page.map(BoardResponse::of).getContent(), page.getNumber(), page.getSize(),
            page.getTotalElements(), page.getTotalPages());
    }
}
