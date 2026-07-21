package com.flowpilot.todo;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "todo.boards")
record BoardProperties(
        @Min(1) @Max(524_288) int requestMaxBytes,
        @Min(1) @Max(100_000) int contentMaxCharacters) {
}
