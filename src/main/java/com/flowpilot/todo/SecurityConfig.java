package com.flowpilot.todo;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http, SecurityProblemWriter problemWriter) throws Exception {
        return http.csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/boards").hasAuthority("SCOPE_boards.admin")
                .requestMatchers(HttpMethod.PATCH, "/api/v1/boards/*").hasAuthority("SCOPE_boards.admin")
                .requestMatchers("/api/v1/boards/*/posts").hasAuthority("SCOPE_boards")
                .requestMatchers("/api/v1/boards", "/api/v1/boards/**")
                    .hasAnyAuthority("SCOPE_boards", "SCOPE_boards.admin")
                .requestMatchers("/api/v1/posts", "/api/v1/posts/**").hasAuthority("SCOPE_boards")
                .requestMatchers("/api/v1/todos", "/api/v1/todos/**").hasAuthority("SCOPE_todos")
                .requestMatchers("/actuator/**").hasAuthority("SCOPE_todos.admin")
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, exception) ->
                    problemWriter.write(request, response, HttpStatus.UNAUTHORIZED,
                        "AUTHENTICATION_REQUIRED", "유효한 인증이 필요합니다."))
                .accessDeniedHandler((request, response, exception) ->
                    problemWriter.write(request, response, HttpStatus.FORBIDDEN,
                        "ACCESS_DENIED", "요청 권한이 없습니다.")))
            .oauth2ResourceServer(oauth -> oauth
                .jwt(Customizer.withDefaults())
                .authenticationEntryPoint((request, response, exception) ->
                    problemWriter.write(request, response, HttpStatus.UNAUTHORIZED,
                        "AUTHENTICATION_REQUIRED", "유효한 인증이 필요합니다."))
                .accessDeniedHandler((request, response, exception) ->
                    problemWriter.write(request, response, HttpStatus.FORBIDDEN,
                        "ACCESS_DENIED", "요청 권한이 없습니다.")))
            .build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${todo.security.allowed-origins:}") String allowedOrigins) {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
            .map(String::trim).filter(value -> !value.isEmpty()).toList();
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "If-Match", "X-Correlation-ID"));
        configuration.setExposedHeaders(List.of("ETag", "Location", "X-Correlation-ID"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
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

@org.springframework.stereotype.Component
class SecurityProblemWriter {
    private final ObjectMapper objectMapper;

    SecurityProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
            String code, String detail) throws IOException {
        if (response.isCommitted()) return;
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("about:blank"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
