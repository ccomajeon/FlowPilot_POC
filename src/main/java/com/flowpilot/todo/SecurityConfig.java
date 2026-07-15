package com.flowpilot.todo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/api/v1/todos", "/api/v1/todos/**").hasAuthority("SCOPE_todos")
                .requestMatchers("/actuator/**").hasAuthority("SCOPE_todos.admin")
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}))
            .build();
    }

    @Bean
    JwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            OAuth2TokenValidator<Jwt> todoJwtValidator) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(todoJwtValidator);
        return decoder;
    }

    @Bean
    OAuth2TokenValidator<Jwt> todoJwtValidator(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${todo.security.audience}") String audience) {
        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> policy = token -> {
            String subject = token.getSubject();
            boolean validAudience = token.getAudience() != null && token.getAudience().contains(audience);
            boolean validSubject = subject != null && !subject.isBlank() && subject.length() <= 255;
            if (!validAudience || !validSubject) {
                return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "JWT policy validation failed", null));
            }
            return OAuth2TokenValidatorResult.success();
        };
        return new DelegatingOAuth2TokenValidator<>(defaults, policy);
    }
}
