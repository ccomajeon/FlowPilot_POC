package com.flowpilot.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = false)
class BoardApiIntegrationTest {
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
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "https://issuer.example/jwks");
        registry.add("todo.security.audience", () -> "todo-api");
    }

    @MockitoBean JwtDecoder jwtDecoder;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired BoardRepository boards;
    @Autowired BoardPostRepository posts;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meterRegistry;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        posts.deleteAll();
        boards.deleteAll();
    }

    @Test
    void menuAuthorizationVisibilityAndOptimisticConcurrencyAreEnforced() throws Exception {
        mvc.perform(get("/api/v1/boards"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
        mvc.perform(post("/api/v1/boards").with(boardUser("user"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"forbidden\",\"displayOrder\":1}"))
            .andExpect(status().isForbidden());

        MvcResult created = createBoard("공지사항", 20, false);
        JsonNode createdBody = objectMapper.readTree(created.getResponse().getContentAsByteArray());
        String boardId = createdBody.get("id").asText();
        assertThat(createdBody.has("createdBy")).isFalse();
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/boards/" + boardId);
        assertThat(created.getResponse().getHeader("ETag")).isEqualTo("\"0\"");

        mvc.perform(post("/api/v1/boards").with(boardAdmin("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"공지사항\",\"displayOrder\":30}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOARD_NAME_CONFLICT"));
        mvc.perform(post("/api/v1/boards").with(boardAdmin("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"unknown\",\"displayOrder\":30,\"createdBy\":\"spoofed\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mvc.perform(get("/api/v1/boards").with(boardUser("user")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/boards?includeInactive=true").with(boardUser("user")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(get("/api/v1/boards?includeInactive=true").with(boardAdmin("admin")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].name").value("공지사항"));

        mvc.perform(patch("/api/v1/boards/{id}", boardId).with(boardAdmin("admin"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
            .andExpect(status().isPreconditionRequired());
        mvc.perform(patch("/api/v1/boards/{id}", boardId).with(boardAdmin("admin"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":true,\"displayOrder\":10}"))
            .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.active").value(true));
        mvc.perform(patch("/api/v1/boards/{id}", boardId).with(boardAdmin("admin"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"stale\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mvc.perform(get("/api/v1/boards/{id}", boardId).with(boardUser("user")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("공지사항"));
    }

    @Test
    void postsAreSharedButOnlyTheAuthorCanChangeOrLogicallyDelete() throws Exception {
        String boardId = boardId(createBoard("공유", 1, true));
        MvcResult created = createPost(boardId, "alice", "제목", "MARKDOWN", "# 안전한 본문");
        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsByteArray());
        String postId = body.get("id").asText();
        assertThat(body.get("isAuthor").asBoolean()).isTrue();
        assertThat(body.has("authorId")).isFalse();

        mvc.perform(get("/api/v1/boards/{id}/posts", boardId).with(boardUser("bob")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].title").value("제목"))
            .andExpect(jsonPath("$.items[0].isAuthor").value(false))
            .andExpect(jsonPath("$.items[0].content").doesNotExist());
        mvc.perform(get("/api/v1/posts/{id}", postId).with(boardUser("bob")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("# 안전한 본문"));

        mvc.perform(patch("/api/v1/posts/{id}", postId).with(boardUser("bob"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"탈취\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("POST_AUTHOR_REQUIRED"));
        mvc.perform(patch("/api/v1/posts/{id}", postId).with(boardUser("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"수정\"}"))
            .andExpect(status().isPreconditionRequired());
        mvc.perform(patch("/api/v1/posts/{id}", postId).with(boardUser("alice"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"<script>alert(1)</script>\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONTENT_POLICY_VIOLATION"));
        mvc.perform(patch("/api/v1/posts/{id}", postId).with(boardUser("alice"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"수정\",\"content\":\"## 변경\"}"))
            .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
        mvc.perform(delete("/api/v1/posts/{id}", postId).with(boardUser("alice"))
                .header("If-Match", "\"0\""))
            .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/posts/{id}", postId).with(boardUser("alice"))
                .header("If-Match", "\"1\""))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/posts/{id}", postId).with(boardUser("alice")))
            .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM board_posts WHERE id = ?",
            Boolean.class, UUID.fromString(postId))).isTrue();
    }

    @Test
    void richTextMassAssignmentPagingInactiveBoardsAndPayloadLimitsAreEnforced() throws Exception {
        String activeBoardId = boardId(createBoard("서식", 1, true));
        String richText = "{\"schemaVersion\":1,\"type\":\"doc\",\"content\":[]}";
        MvcResult richPost = createPost(activeBoardId, "alice", "서식 글", "RICH_TEXT", richText);
        String richPostId = objectMapper.readTree(richPost.getResponse().getContentAsByteArray()).get("id").asText();
        mvc.perform(get("/api/v1/posts/{id}", richPostId).with(boardUser("bob")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.content").value(richText));

        mvc.perform(patch("/api/v1/boards/{id}", activeBoardId).with(boardAdmin("admin"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false}"))
            .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
        mvc.perform(get("/api/v1/posts/{id}", richPostId).with(boardUser("bob")))
            .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM board_posts WHERE id = ?", Long.class,
            UUID.fromString(richPostId))).isEqualTo(1L);
        mvc.perform(patch("/api/v1/boards/{id}", activeBoardId).with(boardAdmin("admin"))
                .header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":true}"))
            .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""));
        mvc.perform(get("/api/v1/posts/{id}", richPostId).with(boardUser("bob")))
            .andExpect(status().isOk());

        mvc.perform(post("/api/v1/boards/{id}/posts", activeBoardId).with(boardUser("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\",\"editorType\":\"MARKDOWN\",\"content\":\"x\",\"authorId\":\"mallory\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/boards/{id}/posts?size=101", activeBoardId).with(boardUser("alice")))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/boards/{id}/posts?sort=title,asc", activeBoardId).with(boardUser("alice")))
            .andExpect(status().isBadRequest());

        String inactiveBoardId = boardId(createBoard("비활성", 2, false));
        mvc.perform(post("/api/v1/boards/{id}/posts", inactiveBoardId).with(boardUser("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\",\"editorType\":\"MARKDOWN\",\"content\":\"x\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("BOARD_INACTIVE"));
        mvc.perform(get("/api/v1/boards/{id}/posts", inactiveBoardId).with(boardUser("alice")))
            .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/boards/{id}/posts", activeBoardId).with(boardAdmin("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\",\"editorType\":\"MARKDOWN\",\"content\":\"x\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/boards/{id}/posts", activeBoardId).with(boardUser("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "title", "large", "editorType", "MARKDOWN", "content", "x".repeat(524_288)))))
            .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void migrationCreatesConstraintsIndexesAndRollsBackFailedWork() {
        List<String> boardConstraints = jdbc.queryForList(
            "SELECT conname FROM pg_constraint WHERE conrelid = 'boards'::regclass", String.class);
        assertThat(boardConstraints).contains("uq_boards_name", "ck_boards_name", "ck_boards_display_order");
        List<String> postConstraints = jdbc.queryForList(
            "SELECT conname FROM pg_constraint WHERE conrelid = 'board_posts'::regclass", String.class);
        assertThat(postConstraints).contains("fk_board_posts_board", "ck_board_posts_editor_type",
            "ck_board_posts_content", "ck_board_posts_deleted");
        assertThat(jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'boards'", String.class))
            .contains("idx_boards_active_order");
        assertThat(jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE tablename = 'board_posts'", String.class))
            .contains("idx_board_posts_visible_created");

        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO boards(id, name, display_order, active, created_by, updated_by, created_at, updated_at, version)
            VALUES (?, ' ', 0, false, 'actor', 'actor', now(), now(), 0)
            """, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);

        long before = boards.count();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            boards.saveAndFlush(new Board("admin", "rollback", null, 1, false));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(boards.count()).isEqualTo(before);
    }

    @Test
    @Timeout(30)
    void actualParallelPostUpdatesAllowExactlyOneCommit() throws Exception {
        Board board = boards.saveAndFlush(new Board("admin", "동시성", null, 1, true));
        BoardPost seed = posts.saveAndFlush(new BoardPost(board, "alice", "seed", EditorType.MARKDOWN, "seed"));
        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Callable<Object> update = () -> concurrentUpdate(seed.id, loaded, release);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(update);
            Future<Object> second = executor.submit(update);
            try {
                assertThat(loaded.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            List<Object> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter("updated"::equals).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(RuntimeException.class::isInstance).count()).isEqualTo(1);
        }
        assertThat(posts.findById(seed.id).orElseThrow().version).isEqualTo(1);
    }

    @Test
    @Timeout(30)
    void boardDeactivationAndPostCreationCannotCommitInTheWrongOrder() throws Exception {
        Board seed = boards.saveAndFlush(new Board("admin", "비활성화 경쟁", null, 1, true));
        CountDownLatch deactivationFlushed = new CountDownLatch(1);
        CountDownLatch releaseDeactivation = new CountDownLatch(1);
        CountDownLatch creationStarted = new CountDownLatch(1);

        Callable<Object> deactivate = () -> {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Board board = boards.findById(seed.id).orElseThrow();
                board.patch("admin", new BoardPatch(null, false, null, null, false));
                boards.saveAndFlush(board);
                deactivationFlushed.countDown();
                await(releaseDeactivation);
            });
            return "deactivated";
        };
        Callable<Object> create = () -> {
            await(deactivationFlushed);
            creationStarted.countDown();
            return new TransactionTemplate(transactionManager).execute(status -> {
                Board board = boards.lockForPostCreation(seed.id).orElseThrow();
                if (!board.active) return "inactive";
                posts.saveAndFlush(new BoardPost(board, "alice", "race", EditorType.MARKDOWN, "race"));
                return "created";
            });
        };

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> deactivation = executor.submit(deactivate);
            assertThat(deactivationFlushed.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Object> creation = executor.submit(create);
            assertThat(creationStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> creation.get(200, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
            releaseDeactivation.countDown();
            assertThat(deactivation.get(10, TimeUnit.SECONDS)).isEqualTo("deactivated");
            assertThat(creation.get(10, TimeUnit.SECONDS)).isEqualTo("inactive");
        } finally {
            releaseDeactivation.countDown();
        }
        assertThat(boards.findById(seed.id).orElseThrow().active).isFalse();
        assertThat(posts.count()).isZero();
    }

    @Test
    void boardOperationsExposeOnlyLowCardinalityMetrics() throws Exception {
        createBoard("metrics", 1, false);
        assertThat(meterRegistry.find("boards.operations")
            .tags("operation", "board_create", "outcome", "success", "reason", "none").counter())
            .isNotNull();
    }

    private Object concurrentUpdate(UUID postId, CountDownLatch loaded, CountDownLatch release) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                BoardPost post = posts.findById(postId).orElseThrow();
                loaded.countDown();
                await(release);
                post.patch(new BoardPostPatch("changed", null));
                posts.saveAndFlush(post);
            });
            return "updated";
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private MvcResult createBoard(String name, int displayOrder, boolean active) throws Exception {
        return mvc.perform(post("/api/v1/boards").with(boardAdmin("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "name", name, "displayOrder", displayOrder, "active", active))))
            .andExpect(status().isCreated()).andReturn();
    }

    private String boardId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).get("id").asText();
    }

    private MvcResult createPost(String boardId, String subject, String title,
            String editorType, String content) throws Exception {
        return mvc.perform(post("/api/v1/boards/{id}/posts", boardId).with(boardUser(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "title", title, "editorType", editorType, "content", content))))
            .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\"")).andReturn();
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
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("concurrency timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrency interrupted", exception);
        }
    }
}
