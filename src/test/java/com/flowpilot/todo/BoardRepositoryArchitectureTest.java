package com.flowpilot.todo;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;

class BoardRepositoryArchitectureTest {
    @Test
    void servicesDependOnlyOnRestrictedRepositoryPorts() throws Exception {
        assertThat(BoardService.class.getDeclaredConstructors())
            .containsExactly(BoardService.class.getDeclaredConstructor(BoardStore.class, BoardMetrics.class));
        assertThat(BoardPostService.class.getDeclaredConstructors())
            .containsExactly(BoardPostService.class.getDeclaredConstructor(
                BoardStore.class, BoardPostStore.class, BoardContentPolicy.class, BoardMetrics.class));
        assertThat(JpaRepository.class.isAssignableFrom(BoardStore.class)).isFalse();
        assertThat(JpaRepository.class.isAssignableFrom(BoardPostStore.class)).isFalse();
    }

    @Test
    void portsDoNotExposeUnrestrictedJpaOperations() {
        assertThat(Arrays.stream(BoardStore.class.getMethods()).map(Method::getName))
            .containsExactlyInAnyOrder("findBoard", "lockForPostCreation", "boards", "saveNew", "saveChanged")
            .doesNotContain("findAll", "deleteById");
        assertThat(Arrays.stream(BoardPostStore.class.getMethods()).map(Method::getName))
            .containsExactlyInAnyOrder("findVisible", "visiblePosts", "saveNew", "saveChanged")
            .doesNotContain("findAll", "deleteById");
    }
}
