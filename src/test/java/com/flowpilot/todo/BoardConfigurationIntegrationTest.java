package com.flowpilot.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

@SpringBootTest(properties = {
    "todo.boards.request-max-bytes=512",
    "todo.boards.content-max-characters=16"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = false)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BoardConfigurationIntegrationTest {
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
    @Autowired BoardProperties properties;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM board_posts");
        jdbc.update("DELETE FROM boards");
    }

    @Test
    void configuredRequestAndContentBoundariesDriveRuntimeBehavior() throws Exception {
        assertThat(properties.requestMaxBytes()).isEqualTo(512);
        assertThat(properties.contentMaxCharacters()).isEqualTo(16);

        MvcResult boardResult = mvc.perform(post("/api/v1/boards").with(boardAdmin("admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"configured\",\"displayOrder\":1,\"active\":true}"))
            .andExpect(status().isCreated()).andReturn();
        String boardId = objectMapper.readTree(boardResult.getResponse().getContentAsByteArray())
            .get("id").asText();

        mvc.perform(post("/api/v1/boards/{id}/posts", boardId).with(boardUser("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(postBody("x".repeat(16))))
            .andExpect(status().isCreated());
        long postCount = jdbc.queryForObject("SELECT count(*) FROM board_posts", Long.class);
        mvc.perform(post("/api/v1/boards/{id}/posts", boardId).with(boardUser("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(postBody("x".repeat(17))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("CONTENT_POLICY_VIOLATION"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM board_posts", Long.class)).isEqualTo(postCount);

        String accepted = exactSizeDescriptionPatch(properties.requestMaxBytes());
        assertThat(accepted.getBytes(StandardCharsets.UTF_8)).hasSize(properties.requestMaxBytes());
        mvc.perform(patch("/api/v1/boards/{id}", boardId).with(boardAdmin("admin"))
                .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content(accepted))
            .andExpect(status().isOk());
        String storedDescription = jdbc.queryForObject(
            "SELECT description FROM boards WHERE id = ?::uuid", String.class, boardId);

        String rejected = exactSizeDescriptionPatch(properties.requestMaxBytes() + 1);
        assertThat(rejected.getBytes(StandardCharsets.UTF_8)).hasSize(properties.requestMaxBytes() + 1);
        mvc.perform(patch("/api/v1/boards/{id}", boardId).with(boardAdmin("admin"))
                .header("If-Match", "\"1\"").contentType(MediaType.APPLICATION_JSON)
                .content(rejected))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        assertThat(jdbc.queryForObject("SELECT description FROM boards WHERE id = ?::uuid",
            String.class, boardId)).isEqualTo(storedDescription);
        assertThat(jdbc.queryForObject("SELECT version FROM boards WHERE id = ?::uuid",
            Long.class, boardId)).isEqualTo(1L);
    }

    private byte[] postBody(String content) throws Exception {
        return objectMapper.writeValueAsBytes(new BoardPostCreate("boundary", EditorType.MARKDOWN, content));
    }

    private static String exactSizeDescriptionPatch(int bytes) {
        String prefix = "{\"description\":\"";
        String suffix = "\"}";
        int contentBytes = bytes - prefix.length() - suffix.length();
        if (contentBytes < 1) throw new IllegalArgumentException("target is too small");
        return prefix + "x".repeat(contentBytes) + suffix;
    }

    private static RequestPostProcessor boardUser(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("scope", "boards"))
            .authorities(new SimpleGrantedAuthority("SCOPE_boards"));
    }

    private static RequestPostProcessor boardAdmin(String subject) {
        return jwt().jwt(token -> token.subject(subject).claim("scope", "boards.admin"))
            .authorities(new SimpleGrantedAuthority("SCOPE_boards.admin"));
    }
}
