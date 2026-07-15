package com.flowpilot.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = false)
class TodoQualityGateTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.9-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
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
    @Autowired JdbcTemplate jdbc;
    @Autowired TodoRepository repository;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM todos");
    }

    @Test
    void securityAndInvalidRequestsUseSafeProblemDetails() throws Exception {
        mvc.perform(get("/api/v1/todos").header("X-Correlation-ID", "qa-correlation"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(header().string("X-Correlation-ID", "qa-correlation"))
            .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
            .andExpect(jsonPath("$.correlationId").value("qa-correlation"));

        mvc.perform(get("/api/v1/todos").with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_other"))))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        mvc.perform(get("/api/v1/todos/not-a-uuid").with(owner("owner-a")))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/todos?status=UNKNOWN").with(owner("owner-a")))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/todos?dueFrom=2026-99-01").with(owner("owner-a")))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/todos?page=-1").with(owner("owner-a")))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/todos?size=101").with(owner("owner-a")))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/todos?sort=title,asc").with(owner("owner-a")))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/todos").with(owner("owner-a"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"   \"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/todos").with(owner("owner-a"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("title", "x".repeat(201)))))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/todos").with(owner("owner-a"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("title", "valid", "description", "x".repeat(5001)))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void filtersOwnershipPaginationAndDeletePreconditionsAreEnforced() throws Exception {
        MvcResult first = create("owner-a", "first", "TODO", "2026-07-20");
        create("owner-a", "second", "DONE", "2026-08-20");
        create("owner-b", "private", "TODO", "2026-07-20");

        mvc.perform(get("/api/v1/todos?status=TODO&dueFrom=2026-07-01&dueTo=2026-07-31&size=1")
                .with(owner("owner-a")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].title").value("first"))
            .andExpect(jsonPath("$.totalElements").value(1));

        JsonNode created = objectMapper.readTree(first.getResponse().getContentAsByteArray());
        String id = created.get("id").asText();
        String etag = first.getResponse().getHeader("ETag");
        mvc.perform(get("/api/v1/todos/" + id).with(owner("owner-b"))).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/todos/" + id).with(owner("owner-b")).header("If-Match", etag)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DONE\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/todos/" + id).with(owner("owner-b")).header("If-Match", etag))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/todos/" + id).with(owner("owner-a")))
            .andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("IF_MATCH_REQUIRED"));
        mvc.perform(delete("/api/v1/todos/" + id).with(owner("owner-a")).header("If-Match", "invalid"))
            .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/todos/" + id).with(owner("owner-a")).header("If-Match", etag))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/todos/" + id).with(owner("owner-a"))).andExpect(status().isNotFound());
    }

    @Test
    void postgresqlTypesConstraintsIndexesAndRollbackAreVerified() {
        Map<String, String> types = jdbc.query("""
            SELECT column_name, data_type || ':' || udt_name AS type_name
              FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name = 'todos'
            """, rs -> {
                var result = new java.util.HashMap<String, String>();
                while (rs.next()) result.put(rs.getString(1), rs.getString(2));
                return result;
            });
        assertThat(types.get("id")).isEqualTo("uuid:uuid");
        assertThat(types.get("due_date")).isEqualTo("date:date");
        assertThat(types.get("created_at")).isEqualTo("timestamp with time zone:timestamptz");
        assertThat(types.get("updated_at")).isEqualTo("timestamp with time zone:timestamptz");
        assertThat(types.get("status")).isEqualTo("character varying:varchar");

        List<String> constraints = jdbc.queryForList(
            "SELECT conname FROM pg_constraint WHERE conrelid = 'todos'::regclass", String.class);
        assertThat(constraints).contains("ck_todos_owner", "ck_todos_title", "ck_todos_description", "ck_todos_status");
        List<String> indexes = jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'todos'", String.class);
        assertThat(indexes).contains("idx_todos_owner_created", "idx_todos_owner_status_created", "idx_todos_owner_due_date");

        assertThatThrownBy(() -> insert(UUID.randomUUID(), "owner", " ", "TODO"))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(UUID.randomUUID(), "owner", "valid", "INVALID"))
            .isInstanceOf(DataIntegrityViolationException.class);

        long before = jdbc.queryForObject("SELECT count(*) FROM todos", Long.class);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            insert(UUID.randomUUID(), "owner", "rolled-back", "TODO");
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM todos", Long.class)).isEqualTo(before);
    }

    @Test
    void actualParallelTransactionsCannotOverwriteEachOther() throws Exception {
        Todo seed = repository.saveAndFlush(new Todo("owner", "seed", null, TodoStatus.TODO, LocalDate.now()));
        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Callable<Object> update = () -> runConcurrentChange(seed.id, loaded, release);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> first = executor.submit(update);
            Future<Object> second = executor.submit(update);
            loaded.await();
            release.countDown();
            List<Object> outcomes = List.of(first.get(), second.get());
            assertThat(outcomes.stream().filter("updated"::equals).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(value -> value instanceof RuntimeException).count()).isEqualTo(1);
        }
        assertThat(repository.findByIdAndOwnerId(seed.id, "owner").orElseThrow().version).isEqualTo(1);
    }

    private Object runConcurrentChange(UUID id, CountDownLatch loaded, CountDownLatch release) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Todo todo = repository.findByIdAndOwnerId(id, "owner").orElseThrow();
                loaded.countDown();
                await(release);
                todo.patch(new TodoPatch("changed", false, null, TodoStatus.IN_PROGRESS, false, null));
                repository.saveAndFlush(todo);
            });
            return "updated";
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private void insert(UUID id, String owner, String title, String status) {
        jdbc.update("""
            INSERT INTO todos(id, owner_id, title, status, due_date, created_at, updated_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, 0)
            """, id, owner, title, status, LocalDate.of(2026, 7, 31), Instant.now(), Instant.now());
    }

    private MvcResult create(String ownerId, String title, String status, String dueDate) throws Exception {
        return mvc.perform(post("/api/v1/todos").with(owner(ownerId)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                    "title", title, "status", status, "dueDate", dueDate))))
            .andExpect(status().isCreated()).andExpect(header().exists("ETag")).andReturn();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor owner(String ownerId) {
        return jwt().jwt(token -> token.subject(ownerId).claim("scope", "todos"))
            .authorities(new SimpleGrantedAuthority("SCOPE_todos"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrency test interrupted", exception);
        }
    }
}
