package com.flowpilot.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TestPostgresImageTest {
    private final String original = System.getProperty("test.postgres.image");

    @AfterEach
    void restoreProperty() {
        if (original == null) {
            System.clearProperty("test.postgres.image");
        } else {
            System.setProperty("test.postgres.image", original);
        }
    }

    @Test
    void rejectsMissingAndFloatingImageReferences() {
        System.clearProperty("test.postgres.image");
        assertThatThrownBy(TestPostgresImage::get)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("approved immutable PostgreSQL 16.9 image digest");

        System.setProperty("test.postgres.image", "postgres:16.9-alpine");
        assertThatThrownBy(TestPostgresImage::get)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("approved immutable PostgreSQL 16.9 image digest");
    }

    @Test
    void acceptsApprovedVersionWithImmutableDigest() {
        String image = "postgres:16.9-alpine@sha256:" + "a".repeat(64);
        System.setProperty("test.postgres.image", image);
        assertThat(TestPostgresImage.get()).isNotNull();
    }
}
