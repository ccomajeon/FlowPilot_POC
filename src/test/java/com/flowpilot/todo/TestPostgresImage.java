package com.flowpilot.todo;

import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.utility.DockerImageName;

final class TestPostgresImage {
    private static final Logger log = LoggerFactory.getLogger(TestPostgresImage.class);
    private static final String PROPERTY = "test.postgres.image";
    private static final Pattern APPROVED_IMAGE = Pattern.compile(
        "^postgres:16\\.9-alpine@sha256:[0-9a-f]{64}$");

    private TestPostgresImage() {}

    static DockerImageName get() {
        String image = System.getProperty(PROPERTY);
        if (image == null || !APPROVED_IMAGE.matcher(image).matches()) {
            throw new IllegalStateException(
                "System property '" + PROPERTY + "' must contain the approved immutable PostgreSQL 16.9 image digest");
        }
        log.info("Using immutable PostgreSQL test image {}", image);
        return DockerImageName.parse(image)
            .asCompatibleSubstituteFor("postgres");
    }
}
