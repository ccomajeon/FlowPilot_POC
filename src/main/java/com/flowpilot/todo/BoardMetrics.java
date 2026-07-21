package com.flowpilot.todo;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
class BoardMetrics {
    private final MeterRegistry registry;

    BoardMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void record(String operation, String outcome, String reason) {
        registry.counter("boards.operations", "operation", operation, "outcome", outcome, "reason", reason)
            .increment();
    }
}
