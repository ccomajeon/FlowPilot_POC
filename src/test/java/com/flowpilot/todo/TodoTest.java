package com.flowpilot.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TodoTest {
    @Test
    void titleAcceptsOneAndTwoHundredCharactersAfterTrimming() {
        Todo shortest = todo(" x ", null, null);
        Todo longest = todo(" " + "x".repeat(200) + " ", null, null);

        assertThat(shortest.title).isEqualTo("x");
        assertThat(longest.title).hasSize(200);
    }

    @Test
    void titleRejectsMoreThanTwoHundredCharacters() {
        assertThatThrownBy(() -> todo("x".repeat(201), null, null))
            .isInstanceOf(BadRequest.class)
            .hasMessage("title is invalid");
    }

    @Test
    void descriptionAcceptsFiveThousandCharacters() {
        Todo todo = todo("title", "x".repeat(5000), null);

        assertThat(todo.description).hasSize(5000);
    }

    @Test
    void descriptionRejectsMoreThanFiveThousandCharacters() {
        assertThatThrownBy(() -> todo("title", "x".repeat(5001), null))
            .isInstanceOf(BadRequest.class)
            .hasMessage("description is too long");
    }

    @Test
    void omittedStatusDefaultsToTodo() {
        Todo todo = todo("title", null, null);

        assertThat(todo.status).isEqualTo(TodoStatus.TODO);
    }

    private static Todo todo(String title, String description, TodoStatus status) {
        return new Todo("owner", title, description, status, null);
    }
}
