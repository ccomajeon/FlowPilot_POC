package com.flowpilot.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.ConnectionCallback;
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
class TodoMissingScenariosIntegrationTest {
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
    @Autowired JdbcTemplate jdbc;
    @Autowired TodoRepository repository;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM todos");
    }

    @Test
    void defaultStatusDueDateRoundTripAndStaleDeleteAreVerified() throws Exception {
        MvcResult created = mvc.perform(post("/api/v1/todos").with(owner("owner-a"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"dated\",\"dueDate\":\"2026-07-31\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("TODO"))
            .andExpect(jsonPath("$.dueDate").value("2026-07-31"))
            .andReturn();

        JsonNode body = objectMapper.readTree(created.getResponse().getContentAsByteArray());
        UUID id = UUID.fromString(body.get("id").asText());
        String originalEtag = created.getResponse().getHeader("ETag");
        assertThat(jdbc.queryForObject("SELECT due_date FROM todos WHERE id = ?", LocalDate.class, id))
            .isEqualTo(LocalDate.of(2026, 7, 31));

        mvc.perform(patch("/api/v1/todos/{id}", id).with(owner("owner-a"))
                .header("If-Match", originalEtag)
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"changed\"}"))
            .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
        mvc.perform(delete("/api/v1/todos/{id}", id).with(owner("owner-a"))
                .header("If-Match", originalEtag))
            .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/todos/{id}", id).with(owner("owner-a"))
                .header("If-Match", "\"1\""))
            .andExpect(status().isNoContent());
    }

    @Test
    void identicalSortValuesUseIdAsDeterministicTieBreaker() throws Exception {
        UUID lower = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID higher = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Instant sameTime = Instant.parse("2026-07-15T00:00:00Z");
        insertAt(lower, "sort-owner", "lower", sameTime);
        insertAt(higher, "sort-owner", "higher", sameTime);

        mvc.perform(get("/api/v1/todos?sort=createdAt,desc").with(owner("sort-owner")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].id").value(higher.toString()))
            .andExpect(jsonPath("$.items[1].id").value(lower.toString()));
    }

    @Test
    void runtimeRoleCannotChangeSchemaOrFlywayHistory() throws Exception {
        String schema = "runtime_role_gate";
        String migratorPassword = UUID.randomUUID().toString();
        String appPassword = UUID.randomUUID().toString();
        String database = jdbc.queryForObject("SELECT current_database()", String.class);

        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE ROLE todo_migrator LOGIN PASSWORD '" + migratorPassword + "'");
                statement.execute("CREATE ROLE todo_app LOGIN PASSWORD '" + appPassword + "'");
                statement.execute("CREATE SCHEMA " + schema + " AUTHORIZATION todo_migrator");
                statement.execute("GRANT CONNECT ON DATABASE " + quoteIdentifier(database) + " TO todo_app");
                statement.execute("GRANT USAGE ON SCHEMA " + schema + " TO todo_app");
                statement.execute("REVOKE CREATE ON SCHEMA " + schema + " FROM todo_app");
                statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE todo_migrator IN SCHEMA " + schema
                    + " REVOKE ALL PRIVILEGES ON TABLES FROM todo_app");
            }
            return null;
        });

        try {
            Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), "todo_migrator", migratorPassword)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(false)
                .locations("classpath:db/migration")
                .load()
                .migrate();

            jdbc.execute("REVOKE ALL PRIVILEGES ON TABLE " + schema + ".flyway_schema_history FROM todo_app");
            jdbc.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE " + schema + ".todos TO todo_app");

            for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                assertThat(hasTablePrivilege("todo_app", schema + ".todos", privilege)).isTrue();
                assertThat(hasTablePrivilege("todo_app", schema + ".flyway_schema_history", privilege)).isFalse();
            }

            try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "todo_app", appPassword);
                    Statement statement = connection.createStatement()) {
                statement.execute("SET search_path TO " + schema);
                try (ResultSet result = statement.executeQuery("SELECT count(*) FROM todos")) {
                    assertThat(result.next()).isTrue();
                }
                assertPrivilegeDenied(statement, "ALTER TABLE todos ADD COLUMN forbidden_column text");
                assertPrivilegeDenied(statement, "DELETE FROM flyway_schema_history");
            }
        } finally {
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                    statement.execute("DROP OWNED BY todo_app");
                    statement.execute("DROP OWNED BY todo_migrator");
                    statement.execute("DROP ROLE todo_app");
                    statement.execute("DROP ROLE todo_migrator");
                }
                return null;
            });
        }
    }

    private boolean hasTablePrivilege(String role, String table, String privilege) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT has_table_privilege(?, ?, ?)", Boolean.class, role, table, privilege));
    }

    private static String quoteIdentifier(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    @Test
    @Timeout(30)
    void patchAndDeleteRaceAllowsOnlyOneCommit() throws Exception {
        Todo seed = repository.saveAndFlush(new Todo("owner", "seed", null, TodoStatus.TODO, null));
        CountDownLatch loaded = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Callable<Object> patch = () -> concurrentMutation(seed.id, false, loaded, release);
        Callable<Object> delete = () -> concurrentMutation(seed.id, true, loaded, release);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Object> patchResult = executor.submit(patch);
            Future<Object> deleteResult = executor.submit(delete);
            try {
                assertThat(loaded.await(10, TimeUnit.SECONDS)).as("both mutations loaded the todo").isTrue();
            } finally {
                release.countDown();
            }
            List<Object> outcomes = List.of(
                patchResult.get(10, TimeUnit.SECONDS), deleteResult.get(10, TimeUnit.SECONDS));
            assertThat(outcomes.stream().filter(value -> value instanceof String).count()).isEqualTo(1);
            assertThat(outcomes.stream().filter(value -> value instanceof RuntimeException).count()).isEqualTo(1);
        }
        repository.findByIdAndOwnerId(seed.id, "owner")
            .ifPresent(todo -> assertThat(todo.version).isEqualTo(1));
    }

    private Object concurrentMutation(UUID id, boolean delete, CountDownLatch loaded, CountDownLatch release) {
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                Todo todo = repository.findByIdAndOwnerId(id, "owner").orElseThrow();
                loaded.countDown();
                await(release);
                if (delete) repository.delete(todo);
                else todo.patch(new TodoPatch("patched", false, null, null, false, null));
                repository.flush();
            });
            return delete ? "deleted" : "patched";
        } catch (RuntimeException exception) {
            return exception;
        }
    }

    private void insertAt(UUID id, String ownerId, String title, Instant time) {
        jdbc.update("""
            INSERT INTO todos(id, owner_id, title, status, created_at, updated_at, version)
            VALUES (?, ?, ?, 'TODO', ?, ?, 0)
            """, id, ownerId, title, time, time);
    }

    private static void assertPrivilegeDenied(Statement statement, String sql) {
        try {
            statement.executeUpdate(sql);
            throw new AssertionError("SQL should have been denied: " + sql);
        } catch (SQLException exception) {
            assertThat(exception.getSQLState()).isEqualTo("42501");
        }
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor owner(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("scope", "todos"))
            .authorities(new SimpleGrantedAuthority("SCOPE_todos"));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrency test latch timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("concurrency test interrupted", exception);
        }
    }
}
