package com.flowpilot.todo;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;

class TodoRepositoryArchitectureTest {
    @Test
    void serviceDependsOnlyOnOwnerRestrictedRepositoryPort() throws Exception {
        var constructor = TodoService.class.getDeclaredConstructor(OwnedTodoRepository.class);

        assertThat(TodoService.class.getDeclaredConstructors()).containsExactly(constructor);
        assertThat(JpaRepository.class.isAssignableFrom(OwnedTodoRepository.class)).isFalse();
    }

    @Test
    void ownerRestrictedPortDoesNotExposeUnscopedRepositoryMethods() {
        assertThat(Arrays.stream(OwnedTodoRepository.class.getMethods())
                .map(Method::getName))
            .containsExactlyInAnyOrder(
                "findByIdAndOwnerId",
                "owned",
                "saveNew",
                "saveOwned",
                "deleteOwned")
            .doesNotContain(
                "findById",
                "findAll",
                "deleteById");
    }
}
