package com.flowpilot.todo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtPolicyTest {
    private final OAuth2TokenValidator<Jwt> validator =
        new SecurityConfig().todoJwtValidator("https://issuer.example", "todo-api");

    @Test
    void acceptsRequiredIssuerAudienceSubjectAndLifetime() {
        assertThat(validator.validate(token(builder -> {})).hasErrors()).isFalse();
    }

    @Test
    void rejectsWrongIssuerAudienceExpiredBlankAndOversizedSubjects() {
        assertThat(validator.validate(token(builder -> builder.issuer("https://other.example"))).hasErrors()).isTrue();
        assertThat(validator.validate(token(builder -> builder.audience(List.of("other-api")))).hasErrors()).isTrue();
        Instant expiredAt = Instant.now().minusSeconds(90);
        assertThat(validator.validate(token(builder -> builder
            .issuedAt(expiredAt.minusSeconds(60))
            .notBefore(expiredAt.minusSeconds(60))
            .expiresAt(expiredAt))).hasErrors()).isTrue();
        assertThat(validator.validate(token(builder -> builder.subject(" "))).hasErrors()).isTrue();
        assertThat(validator.validate(token(builder -> builder.subject("x".repeat(256)))).hasErrors()).isTrue();
    }

    private Jwt token(Consumer<Jwt.Builder> customization) {
        Instant now = Instant.now();
        Jwt.Builder builder = Jwt.withTokenValue("test-token")
            .header("alg", "RS256")
            .issuer("https://issuer.example")
            .subject("stable-user-id")
            .audience(List.of("todo-api"))
            .issuedAt(now.minusSeconds(10))
            .notBefore(now.minusSeconds(10))
            .expiresAt(now.plusSeconds(300));
        customization.accept(builder);
        return builder.build();
    }
}
