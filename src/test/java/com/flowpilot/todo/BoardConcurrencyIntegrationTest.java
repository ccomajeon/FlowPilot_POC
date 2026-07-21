package com.flowpilot.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = false)
@Import(BoardConcurrencyIntegrationTest.RaceConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BoardConcurrencyIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(TestPostgresImage.get());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "https://issuer.example");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
            () -> "https://issuer.example/jwks");
        registry.add("todo.security.audience", () -> "todo-api");
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired BoardRepository boards;
    @Autowired BoardPostRepository posts;
    @Autowired RacingBoardPostStore racingStore;

    @BeforeEach
    void cleanDatabase() {
        racingStore.clear();
        posts.deleteAll();
        boards.deleteAll();
    }

    @Test
    @Timeout(60)
    void parallelPatchRequestsCommitOnceAndReturnOneStableVersionConflict() throws Exception {
        String boardId = createBoard("parallel-patch");
        for (int iteration = 0; iteration < 3; iteration++) {
            String postId = createPost(boardId, "patch-seed-" + iteration, "seed-content-" + iteration);
            racingStore.arm(UUID.fromString(postId));
            String firstTitle = "first-patch-" + iteration;
            String secondTitle = "second-patch-" + iteration;

            List<HttpOutcome> outcomes = runRace(
                () -> patchPost(postId, firstTitle, null),
                () -> patchPost(postId, secondTitle, null));

            assertOneSuccessAndOneConflict(outcomes, Set.of(200));
            Map<String, Object> state = postState(postId);
            assertThat(((Number) state.get("version")).longValue()).isEqualTo(1L);
            assertThat(state.get("title")).isIn(firstTitle, secondTitle);
            assertThat(state.get("content")).isEqualTo("seed-content-" + iteration);
            assertThat(state.get("deleted_at")).isNull();
        }
    }

    @Test
    @Timeout(60)
    void parallelPatchAndDeleteCommitOnceAndReturnOneStableVersionConflict() throws Exception {
        String boardId = createBoard("parallel-patch-delete");
        for (int iteration = 0; iteration < 3; iteration++) {
            String seedContent = "delete-seed-" + iteration;
            String patchedContent = "patched-content-" + iteration;
            String postId = createPost(boardId, "delete-title-" + iteration, seedContent);
            racingStore.arm(UUID.fromString(postId));

            List<HttpOutcome> outcomes = runRace(
                () -> patchPost(postId, null, patchedContent),
                () -> deletePost(postId));

            assertOneSuccessAndOneConflict(outcomes, Set.of(200, 204));
            Map<String, Object> state = postState(postId);
            assertThat(((Number) state.get("version")).longValue()).isEqualTo(1L);
            HttpOutcome successful = outcomes.stream()
                .filter(outcome -> outcome.status() == 200 || outcome.status() == 204)
                .findFirst().orElseThrow();
            if (successful.status() == 200) {
                assertThat(state.get("content")).isEqualTo(patchedContent);
                assertThat(state.get("deleted_at")).isNull();
                assertThat(state.get("deleted_by")).isNull();
            } else {
                assertThat(state.get("content")).isEqualTo(seedContent);
                assertThat(state.get("deleted_at")).isNotNull();
                assertThat(state.get("deleted_by")).isEqualTo("alice");
            }
        }
    }

    private List<HttpOutcome> runRace(ThrowingOutcome firstTask, ThrowingOutcome secondTask) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<HttpOutcome> first = executor.submit(() -> {
                await(start);
                return firstTask.run();
            });
            Future<HttpOutcome> second = executor.submit(() -> {
                await(start);
                return secondTask.run();
            });
            start.countDown();
            try {
                assertThat(racingStore.awaitBothLoaded(10, TimeUnit.SECONDS))
                    .as("both HTTP requests loaded the same persisted version").isTrue();
            } finally {
                racingStore.release();
            }
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            racingStore.release();
            racingStore.clear();
        }
    }

    private void assertOneSuccessAndOneConflict(List<HttpOutcome> outcomes, Set<Integer> successStatuses) {
        assertThat(outcomes.stream().filter(outcome -> successStatuses.contains(outcome.status())).count())
            .isEqualTo(1L);
        assertThat(outcomes.stream().filter(outcome -> outcome.status() == 409).count()).isEqualTo(1L);
        assertThat(outcomes.stream().filter(outcome -> outcome.status() == 409)
            .map(HttpOutcome::code)).containsExactly("VERSION_CONFLICT");
    }

    private HttpOutcome patchPost(String postId, String title, String content) throws Exception {
        java.util.HashMap<String, String> patch = new java.util.HashMap<>();
        if (title != null) patch.put("title", title);
        if (content != null) patch.put("content", content);
        MvcResult result = mvc.perform(patch("/api/v1/posts/{id}", postId).with(boardUser("alice"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(patch)))
            .andReturn();
        return outcome("PATCH", result);
    }

    private HttpOutcome deletePost(String postId) throws Exception {
        MvcResult result = mvc.perform(delete("/api/v1/posts/{id}", postId).with(boardUser("alice"))
                .header("If-Match", "\"0\""))
            .andReturn();
        return outcome("DELETE", result);
    }

    private HttpOutcome outcome(String operation, MvcResult result) throws Exception {
        int status = result.getResponse().getStatus();
        String code = null;
        if (status == 409) {
            JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
            code = body.path("code").asText();
        }
        return new HttpOutcome(operation, status, code);
    }

    private String createBoard(String name) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/boards").with(boardAdmin("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "name", name, "displayOrder", 1, "active", true))))
            .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("id").asText();
    }

    private String createPost(String boardId, String title, String content) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/boards/{id}/posts", boardId).with(boardUser("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "title", title, "editorType", "MARKDOWN", "content", content))))
            .andExpect(status().isCreated()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("id").asText();
    }

    private Map<String, Object> postState(String postId) {
        return jdbc.queryForMap("""
            SELECT title, content, version, deleted_at, deleted_by
              FROM board_posts WHERE id = ?::uuid
            """, postId);
    }

    private static RequestPostProcessor boardUser(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("scope", "boards"))
            .authorities(new SimpleGrantedAuthority("SCOPE_boards"));
    }

    private static RequestPostProcessor boardAdmin(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("scope", "boards.admin"))
            .authorities(new SimpleGrantedAuthority("SCOPE_boards.admin"));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("race start timed out");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("race interrupted", exception);
        }
    }

    @FunctionalInterface
    private interface ThrowingOutcome {
        HttpOutcome run() throws Exception;
    }

    private record HttpOutcome(String operation, int status, String code) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class RaceConfiguration {
        @Bean
        @Primary
        RacingBoardPostStore racingBoardPostStore(BoardPostRepository delegate) {
            return new RacingBoardPostStore(delegate);
        }
    }

    static final class RacingBoardPostStore implements BoardPostStore {
        private final BoardPostRepository delegate;
        private final AtomicReference<Race> activeRace = new AtomicReference<>();

        RacingBoardPostStore(BoardPostRepository delegate) {
            this.delegate = delegate;
        }

        void arm(UUID postId) {
            if (!activeRace.compareAndSet(null, new Race(postId))) {
                throw new IllegalStateException("a race is already active");
            }
        }

        boolean awaitBothLoaded(long timeout, TimeUnit unit) throws InterruptedException {
            Race race = activeRace.get();
            return race != null && race.loaded.await(timeout, unit);
        }

        void release() {
            Race race = activeRace.get();
            if (race != null) race.release.countDown();
        }

        void clear() {
            activeRace.set(null);
        }

        @Override
        public Optional<BoardPost> findVisible(UUID id) {
            Optional<BoardPost> result = delegate.findVisible(id);
            Race race = activeRace.get();
            if (result.isPresent() && race != null && race.postId.equals(id)
                    && race.remainingParticipants.getAndDecrement() > 0) {
                race.loaded.countDown();
                await(race.release);
            }
            return result;
        }

        @Override
        public Page<BoardPost> visiblePosts(UUID boardId, Pageable pageable) {
            return delegate.visiblePosts(boardId, pageable);
        }

        @Override
        public BoardPost saveNew(BoardPost post) {
            return delegate.saveNew(post);
        }

        @Override
        public BoardPost saveChanged(BoardPost post) {
            return delegate.saveChanged(post);
        }

        private static final class Race {
            private final UUID postId;
            private final CountDownLatch loaded = new CountDownLatch(2);
            private final CountDownLatch release = new CountDownLatch(1);
            private final AtomicInteger remainingParticipants = new AtomicInteger(2);

            private Race(UUID postId) {
                this.postId = postId;
            }
        }
    }
}
