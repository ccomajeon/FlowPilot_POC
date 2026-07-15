package com.flowpilot.todo;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class TodoApiIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "https://issuer.invalid");
        r.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "https://issuer.invalid/jwks");
    }
    @Autowired MockMvc mvc;
    @Autowired TodoRepository repository;
    @BeforeEach void clean() { repository.deleteAll(); }

    @Test void crudOwnershipAndConcurrency() throws Exception {
        String body = mvc.perform(post("/api/v1/todos").with(jwt().jwt(j -> j.subject("alice")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" task \",\"dueDate\":\"2026-07-31\"}"))
            .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\""))
            .andExpect(jsonPath("$.title").value("task")).andReturn().getResponse().getContentAsString();
        String id = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asText();

        mvc.perform(get("/api/v1/todos/{id}", id).with(jwt().jwt(j -> j.subject("bob"))))
            .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/todos/{id}", id).with(jwt().jwt(j -> j.subject("alice")))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"DONE\",\"dueDate\":null}"))
            .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.status").value("DONE")).andExpect(jsonPath("$.dueDate").doesNotExist());
        mvc.perform(patch("/api/v1/todos/{id}", id).with(jwt().jwt(j -> j.subject("alice")))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"stale\"}"))
            .andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/todos/{id}", id).with(jwt().jwt(j -> j.subject("alice"))).header("If-Match", "\"1\""))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/todos/{id}", id).with(jwt().jwt(j -> j.subject("alice"))))
            .andExpect(status().isNotFound());
    }

    @Test void validatesAuthenticationAndInput() throws Exception {
        mvc.perform(get("/api/v1/todos")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/todos").with(jwt()).contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" \"}"))
            .andExpect(status().isBadRequest());
    }
}
