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
        Map<String, Object> beforeStalePatch = boardSnapshot(boardId);
        mvc.perform(patch("/api/v1/boards/{id}", boardId).with(boardAdmin("admin"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"stale\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(boardSnapshot(boardId)).isEqualTo(beforeStalePatch);
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

        Map<String, Object> beforeRejectedChanges = postSnapshot(postId);
        mvc.perform(patch("/api/v1/posts/{id}", postId).with(boardUser("bob"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"탈취\"}"))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("POST_AUTHOR_REQUIRED"));
        assertThat(postSnapshot(postId)).isEqualTo(beforeRejectedChanges);
        mvc.perform(patch("/api/v1/posts/{id}", postId).with(boardUser("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"수정\"}"))
            .andExpect(status().isPreconditionRequired());
        mvc.perform(patch("/api/v1/posts/{id}", postId).with(boardUser("alice"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"<script>alert(1)</script>\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CONTENT_POLICY_VIOLATION"));
        assertThat(postSnapshot(postId)).isEqualTo(beforeRejectedChanges);
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
    void boardAndPostOrderingUsesDeterministicUuidTieBreakers() throws Exception {
        String boardOne = boardId(createBoard("same-order-one", 5, true));
        String boardTwo = boardId(createBoard("same-order-two", 5, true));
        String boardThree = boardId(createBoard("same-order-three", 5, true));

        List<String> expectedBoards = jdbc.queryForList(
            "SELECT id::text FROM boards WHERE active = true ORDER BY display_order ASC, id ASC", String.class);
        MvcResult boardPage = mvc.perform(get("/api/v1/boards?size=100").with(boardUser("reader")))
            .andExpect(status().isOk()).andReturn();
        assertThat(responseIds(boardPage)).containsExactlyElementsOf(expectedBoards);

        createPost(boardOne, "alice", "first", "MARKDOWN", "first");
        createPost(boardOne, "alice", "second", "MARKDOWN", "second");
        createPost(boardOne, "alice", "third", "MARKDOWN", "third");
        jdbc.update("UPDATE board_posts SET created_at = '2026-07-21T00:00:00Z' WHERE board_id = ?::uuid",
            boardOne);

        List<String> expectedDescending = jdbc.queryForList(
            "SELECT id::text FROM board_posts WHERE board_id = ?::uuid "
                + "ORDER BY created_at DESC, id DESC", String.class, boardOne);
        MvcResult descending = mvc.perform(get("/api/v1/boards/{id}/posts?sort=createdAt,desc&size=100",
                boardOne).with(boardUser("reader")))
            .andExpect(status().isOk()).andReturn();
        assertThat(responseIds(descending)).containsExactlyElementsOf(expectedDescending);

        List<String> expectedAscending = jdbc.queryForList(
            "SELECT id::text FROM board_posts WHERE board_id = ?::uuid "
                + "ORDER BY created_at ASC, id ASC", String.class, boardOne);
        MvcResult ascending = mvc.perform(get("/api/v1/boards/{id}/posts?sort=createdAt,asc&size=100",
                boardOne).with(boardUser("reader")))
            .andExpect(status().isOk()).andReturn();
        assertThat(responseIds(ascending)).containsExactlyElementsOf(expectedAscending);
        assertThat(List.of(boardOne, boardTwo, boardThree)).containsExactlyInAnyOrderElementsOf(expectedBoards);
    }

    @Test
    void editorAndExactFieldBoundariesRejectInvalidRequestsWithoutPersistence() throws Exception {
        String maxName = "n".repeat(100);
        MvcResult boardResult = mvc.perform(post("/api/v1/boards").with(boardAdmin("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "name", maxName, "description", "d".repeat(1000),
                    "displayOrder", 1, "active", true))))
            .andExpect(status().isCreated()).andReturn();
        String boardId = boardId(boardResult);
        long boardCount = boards.count();

        mvc.perform(post("/api/v1/boards").with(boardAdmin("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "name", "n".repeat(101), "displayOrder", 2))))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/boards").with(boardAdmin("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "name", "description-too-long", "description", "d".repeat(1001),
                    "displayOrder", 2))))
            .andExpect(status().isBadRequest());
        assertThat(boards.count()).isEqualTo(boardCount);

        MvcResult maxPost = createPost(boardId, "alice", "t".repeat(200), "MARKDOWN",
            "c".repeat(100_000));
        String postId = objectMapper.readTree(maxPost.getResponse().getContentAsByteArray()).get("id").asText();
        assertThat(jdbc.queryForObject("SELECT length(content) FROM board_posts WHERE id = ?::uuid",
            Integer.class, postId)).isEqualTo(100_000);
        long postCount = posts.count();

        for (String body : List.of(
                "{\"title\":\"valid\",\"editorType\":\"HTML\",\"content\":\"valid\"}",
                "{\"title\":\"valid\",\"editorType\":\"\",\"content\":\"valid\"}",
                "{\"title\":\"   \",\"editorType\":\"MARKDOWN\",\"content\":\"valid\"}",
                "{\"title\":\"valid\",\"editorType\":\"MARKDOWN\",\"content\":\"   \"}")) {
            mvc.perform(post("/api/v1/boards/{id}/posts", boardId).with(boardUser("alice"))
                    .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/v1/boards/{id}/posts", boardId).with(boardUser("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "title", "t".repeat(201), "editorType", "MARKDOWN", "content", "valid"))))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/boards/{id}/posts", boardId).with(boardUser("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                    "title", "valid", "editorType", "MARKDOWN", "content", "c".repeat(100_001)))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("CONTENT_POLICY_VIOLATION"));
        assertThat(posts.count()).isEqualTo(postCount);
        assertThat(jdbc.queryForObject("SELECT version FROM board_posts WHERE id = ?::uuid",
            Long.class, postId)).isZero();
    }

    @Test
    void unknownPatchesLeaveEveryPersistedFieldAndVersionUnchanged() throws Exception {
        String boardId = boardId(createBoard("unknown-patch", 7, true));
        MvcResult postResult = createPost(boardId, "alice", "original", "MARKDOWN", "original-content");
        String postId = objectMapper.readTree(postResult.getResponse().getContentAsByteArray()).get("id").asText();
        Map<String, Object> boardBefore = boardSnapshot(boardId);
        Map<String, Object> postBefore = postSnapshot(postId);

        mvc.perform(patch("/api/v1/boards/{id}", boardId).with(boardAdmin("admin"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"unknown\":true}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(patch("/api/v1/posts/{id}", postId).with(boardUser("alice"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"editorType\":\"RICH_TEXT\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(patch("/api/v1/posts/{id}", postId).with(boardUser("alice"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of("content", "c".repeat(100_001)))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("CONTENT_POLICY_VIOLATION"));

        assertThat(boardSnapshot(boardId)).isEqualTo(boardBefore);
        assertThat(postSnapshot(postId)).isEqualTo(postBefore);
    }

    @Test
    void boardTodoAndCombinedScopesRemainStrictlySeparated() throws Exception {
        mvc.perform(get("/api/v1/boards").with(todoAdmin("operator")))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mvc.perform(post("/api/v1/boards").with(todoAdmin("operator"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"forbidden\",\"displayOrder\":1}"))
            .andExpect(status().isForbidden());
        assertThat(boards.count()).isZero();

        MvcResult board = mvc.perform(post("/api/v1/boards").with(boardAdminAndUser("board-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"combined\",\"displayOrder\":1,\"active\":true}"))
            .andExpect(status().isCreated()).andReturn();
        String boardId = boardId(board);
        mvc.perform(post("/api/v1/boards/{id}/posts", boardId).with(boardAdmin("admin-only"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"forbidden\",\"editorType\":\"MARKDOWN\",\"content\":\"x\"}"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/boards/{id}/posts", boardId).with(boardAdminAndUser("board-admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"allowed\",\"editorType\":\"MARKDOWN\",\"content\":\"x\"}"))
            .andExpect(status().isCreated());
        mvc.perform(get("/actuator/prometheus").with(boardAdminAndUser("board-admin")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus").with(todoAdmin("operator")))
            .andExpect(status().isOk());
        assertThat(posts.count()).isEqualTo(1L);
    }

    @Test
    void migrationCreatesConstraintsIndexesAndRollsBackFailedWork() {
        Map<String, String> columnTypes = jdbc.query("""
            SELECT column_name, format_type(a.atttypid, a.atttypmod) AS type_name
              FROM pg_attribute a
              JOIN pg_class c ON c.oid = a.attrelid
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = 'public' AND c.relname = 'board_posts'
               AND a.attnum > 0 AND NOT a.attisdropped
            """, resultSet -> {
                Map<String, String> result = new java.util.HashMap<>();
                while (resultSet.next()) result.put(resultSet.getString(1), resultSet.getString(2));
                return result;
            });
        assertThat(columnTypes).containsEntry("id", "uuid")
            .containsEntry("board_id", "uuid")
            .containsEntry("author_id", "character varying(255)")
            .containsEntry("title", "character varying(200)")
            .containsEntry("editor_type", "character varying(20)")
            .containsEntry("content", "text")
            .containsEntry("created_at", "timestamp with time zone")
            .containsEntry("updated_at", "timestamp with time zone")
            .containsEntry("deleted_at", "timestamp with time zone")
            .containsEntry("deleted_by", "character varying(255)")
            .containsEntry("version", "bigint");

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
        assertThat(jdbc.queryForObject(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'fk_board_posts_board'",
            String.class)).isEqualTo("FOREIGN KEY (board_id) REFERENCES boards(id) ON DELETE RESTRICT");
        assertThat(jdbc.queryForObject(
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_board_posts_editor_type'",
            String.class)).contains("MARKDOWN", "RICH_TEXT");
        Map<String, Object> visibleIndex = jdbc.queryForMap("""
            SELECT pg_get_indexdef(i.indexrelid) AS definition,
                   pg_get_expr(i.indpred, i.indrelid) AS predicate
              FROM pg_index i
              JOIN pg_class index_class ON index_class.oid = i.indexrelid
             WHERE index_class.relname = 'idx_board_posts_visible_created'
            """);
        assertThat(visibleIndex.get("definition").toString())
            .contains("(board_id, created_at DESC, id DESC)");
        assertThat(visibleIndex.get("predicate").toString()).isEqualTo("(deleted_at IS NULL)");

        long postCount = posts.count();
        UUID validBoardId = boards.saveAndFlush(new Board("admin", "constraint-parent", null, 1, true)).id;
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO boards(id, name, display_order, active, created_by, updated_by, created_at, updated_at, version)
            VALUES (?, ' ', 0, false, 'actor', 'actor', now(), now(), 0)
            """, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO board_posts(id, board_id, author_id, title, editor_type, content,
                                    created_at, updated_at, version)
            VALUES (?, ?, 'author', 'title', 'HTML', 'content', now(), now(), 0)
            """, UUID.randomUUID(), validBoardId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO board_posts(id, board_id, author_id, title, editor_type, content,
                                    created_at, updated_at, version)
            VALUES (?, ?, 'author', 'title', 'MARKDOWN', 'content', now(), now(), 0)
            """, UUID.randomUUID(), UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(posts.count()).isEqualTo(postCount);

        long before = boards.count();
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            boards.saveAndFlush(new Board("admin", "rollback", null, 1, false));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(boards.count()).isEqualTo(before);
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
    void boardOperationsExposeLowCardinalityAndNormalizedHttpMetrics() throws Exception {
        String boardId = boardId(createBoard("metrics", 1, true));
        MvcResult post = createPost(boardId, "metrics-user", "metrics", "MARKDOWN", "metrics");
        String postId = objectMapper.readTree(post.getResponse().getContentAsByteArray()).get("id").asText();
        mvc.perform(get("/api/v1/posts/{id}", postId).with(boardUser("metrics-reader")))
            .andExpect(status().isOk());

        assertThat(meterRegistry.find("boards.operations")
            .tags("operation", "board_create", "outcome", "success", "reason", "none").counter())
            .isNotNull();
        var postTimer = meterRegistry.find("http.server.requests")
            .tags("method", "GET", "status", "200", "uri", "/api/v1/posts/{postId}").timer();
        assertThat(postTimer).isNotNull();
        assertThat(postTimer.count()).isPositive();
        assertThat(postTimer.getId().getTags())
            .noneMatch(tag -> tag.getValue().equals(postId) || tag.getValue().equals(boardId));
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

    private List<String> responseIds(MvcResult result) throws Exception {
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("items");
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        items.forEach(item -> ids.add(item.path("id").asText()));
        return ids;
    }

    private Map<String, Object> boardSnapshot(String boardId) {
        return jdbc.queryForMap("""
            SELECT name, description, display_order, active, updated_by, updated_at, version
              FROM boards WHERE id = ?::uuid
            """, boardId);
    }

    private Map<String, Object> postSnapshot(String postId) {
        return jdbc.queryForMap("""
            SELECT title, editor_type, content, updated_at, deleted_at, deleted_by, version
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

    private static RequestPostProcessor boardAdminAndUser(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("scope", "boards boards.admin"))
            .authorities(new SimpleGrantedAuthority("SCOPE_boards"),
                new SimpleGrantedAuthority("SCOPE_boards.admin"));
    }

    private static RequestPostProcessor todoAdmin(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("scope", "todos.admin"))
            .authorities(new SimpleGrantedAuthority("SCOPE_todos.admin"));
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
