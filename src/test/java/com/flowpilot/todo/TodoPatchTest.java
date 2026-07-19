package com.flowpilot.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class TodoPatchTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void omittedNullableFieldsRemainUnchanged() throws Exception {
        TodoPatch patch = TodoPatch.from(objectMapper.readTree("{\"title\":\"changed\"}"));

        assertThat(patch.title()).isEqualTo("changed");
        assertThat(patch.descriptionPresent()).isFalse();
        assertThat(patch.dueDatePresent()).isFalse();
        assertThat(patch.status()).isNull();
    }

    @Test
    void explicitNullClearsNullableFields() throws Exception {
        TodoPatch patch = TodoPatch.from(objectMapper.readTree(
            "{\"description\":null,\"dueDate\":null}"));

        assertThat(patch.descriptionPresent()).isTrue();
        assertThat(patch.description()).isNull();
        assertThat(patch.dueDatePresent()).isTrue();
        assertThat(patch.dueDate()).isNull();
    }

    @Test
    void providedValuesAreParsedWithoutLosingPresence() throws Exception {
        TodoPatch patch = TodoPatch.from(objectMapper.readTree("""
            {"description":"detail","status":"DONE","dueDate":"2026-07-31"}
            """));

        assertThat(patch.descriptionPresent()).isTrue();
        assertThat(patch.description()).isEqualTo("detail");
        assertThat(patch.status()).isEqualTo(TodoStatus.DONE);
        assertThat(patch.dueDatePresent()).isTrue();
        assertThat(patch.dueDate()).isEqualTo(LocalDate.of(2026, 7, 31));
    }

    @Test
    void descriptionBoundaryIsEnforced() throws Exception {
        TodoPatch accepted = TodoPatch.from(objectMapper.createObjectNode()
            .put("description", "x".repeat(5000)));

        assertThat(accepted.description()).hasSize(5000);
        assertThatThrownBy(() -> TodoPatch.from(objectMapper.createObjectNode()
                .put("description", "x".repeat(5001))))
            .isInstanceOf(BadRequest.class)
            .hasMessage("description is too long");
    }
}
