package com.flowpilot.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TodoApiIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(TestPostgresImage.get());
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        r.add("spring.flyway.user", POSTGRES::getUsername);
        r.add("spring.flyway.password", POSTGRES::getPassword);
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "https://issuer.invalid");
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "https://issuer.invalid/jwks");
        r.add("todo.security.audience", () -> "todo-api");
    }
    @Autowired MockMvc mvc;
    @Autowired TodoRepository repository;
    @Autowired MeterRegistry meterRegistry;
    @BeforeEach void clean() { repository.deleteAll(); }

    @Test void crudOwnershipAndConcurrency() throws Exception {
        String body = mvc.perform(post("/api/v1/todos").with(user("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" task \",\"dueDate\":\"2026-07-31\"}"))
            .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\""))
            .andExpect(jsonPath("$.title").value("task")).andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asText();

        mvc.perform(get("/api/v1/todos/{id}", id).with(user("bob")))
            .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/todos/{id}", id).with(user("alice"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DONE\",\"dueDate\":null}"))
            .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.status").value("DONE")).andExpect(jsonPath("$.dueDate").doesNotExist());
        mvc.perform(patch("/api/v1/todos/{id}", id).with(user("alice"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"stale\"}"))
            .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/todos/{id}", id).with(user("alice")).header("If-Match", "\"1\""))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/todos/{id}", id).with(user("alice")))
            .andExpect(status().isNotFound());
    }

    @Test void validatesAuthenticationAndInput() throws Exception {
        mvc.perform(get("/api/v1/todos")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/todos").with(jwt())).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/todos").with(user("alice")).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" \"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.instance").value("/api/v1/todos"));

        String maximumTitle = "x".repeat(200);
        mvc.perform(post("/api/v1/todos").with(user("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\" " + maximumTitle + " \"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.title").value(maximumTitle));

        mvc.perform(post("/api/v1/todos").with(user("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\" " + "x".repeat(201) + " \"}"))
            .andExpect(status().isBadRequest());
    }

    @Test void rejectsUnsafePatchAndEtagInputs() throws Exception {
        String body = mvc.perform(post("/api/v1/todos").with(user("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"task\"}"))
            .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asText();

        mvc.perform(patch("/api/v1/todos/{id}", id).with(user("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isPreconditionRequired());
        mvc.perform(patch("/api/v1/todos/{id}", id).with(user("alice"))
                .header("If-Match", "\"999999999999999999999999999999\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/todos/{id}", id).with(user("alice"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"description\":123}"))
            .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/todos/{id}", id).with(user("alice"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":null}"))
            .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/v1/todos/{id}", id).with(user("alice"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"unknown\":true}"))
            .andExpect(status().isBadRequest());
    }

    @Test void hidesOwnedResourceForPatchAndDelete() throws Exception {
        String body = mvc.perform(post("/api/v1/todos").with(user("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"private\"}"))
            .andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asText();

        mvc.perform(patch("/api/v1/todos/{id}", id).with(user("bob")).header("If-Match", "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"stolen\"}"))
            .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/todos/{id}", id).with(user("bob")).header("If-Match", "\"0\""))
            .andExpect(status().isNotFound());
    }

    @Test void recordsCoreApiLatencyMetricsWithConfiguredPercentiles() throws Exception {
        mvc.perform(get("/api/v1/todos").with(user("metrics-owner")))
            .andExpect(status().isOk());

        var timer = meterRegistry.find("http.server.requests")
            .tag("uri", "/api/v1/todos")
            .timer();
        assertThat(timer).isNotNull();
        assertThat(timer.count()).isPositive();
        assertThat(timer.takeSnapshot().percentileValues())
            .extracting(value -> value.percentile())
            .contains(0.95, 0.99);
    }

    private static RequestPostProcessor user(String subject) {
        return jwt().jwt(j -> j.subject(subject))
            .authorities(new SimpleGrantedAuthority("SCOPE_todos"));
    }
}
