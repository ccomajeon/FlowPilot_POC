package com.flowpilot.todo;

import org.testcontainers.utility.DockerImageName;

final class TestPostgresImage {
    private static final String PROPERTY = "test.postgres.image";
    private static final String DEFAULT_IMAGE = "postgres:16.9-alpine";

    private TestPostgresImage() {}

    static DockerImageName get() {
        return DockerImageName.parse(System.getProperty(PROPERTY, DEFAULT_IMAGE))
            .asCompatibleSubstituteFor("postgres");
    }
}
