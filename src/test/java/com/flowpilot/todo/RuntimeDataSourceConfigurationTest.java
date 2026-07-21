package com.flowpilot.todo;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.env.YamlPropertySourceLoader;

class RuntimeDataSourceConfigurationTest {
    @Test
    void productionConnectionTimeoutsAreExplicitAndOverridable() throws IOException {
        var sources = new YamlPropertySourceLoader().load(
            "application", new ClassPathResource("application.yml"));

        assertThat(sources).hasSize(1);
        var source = sources.getFirst();
        assertThat(source.getProperty("spring.datasource.hikari.connection-timeout"))
            .isEqualTo("${DB_CONNECTION_TIMEOUT_MS:3000}");
        assertThat(source.getProperty("spring.datasource.hikari.validation-timeout"))
            .isEqualTo("${DB_VALIDATION_TIMEOUT_MS:1000}");
        assertThat(source.getProperty("spring.jackson.deserialization.fail-on-unknown-properties"))
            .isEqualTo(true);
        assertThat(source.getProperty("management.endpoints.web.exposure.include"))
            .isEqualTo("health,info,metrics,prometheus");
        assertThat(source.getProperty("todo.boards.request-max-bytes")).isEqualTo(524288);
        assertThat(source.getProperty("todo.boards.content-max-characters")).isEqualTo(100000);
    }
}
