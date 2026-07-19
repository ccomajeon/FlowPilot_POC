package com.flowpilot.todo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

@ExtendWith(OutputCaptureExtension.class)
class SensitiveLoggingTest {
    @Test
    void exceptionMessagesAndSensitiveRequestValuesAreNotLoggedOrReturned(CapturedOutput output) {
        String token = "Bearer qa-token-marker";
        String databaseUrl = "jdbc:postgresql://db.example/todos?password=qa-password-marker";
        String todoBody = "qa-private-description-marker";
        MDC.put(CorrelationIdFilter.MDC_KEY, "safe-correlation");
        ProblemDetail problem;
        try {
            problem = new ApiExceptionHandler().databaseUnavailable(
                new DataAccessResourceFailureException(
                    "Authorization=" + token + "; url=" + databaseUrl + "; body=" + todoBody));
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(problem.getDetail()).doesNotContain(token, databaseUrl, todoBody);
        assertThat(output.getAll())
            .contains("DataAccessResourceFailureException")
            .contains("safe-correlation")
            .doesNotContain(token, databaseUrl, todoBody, "qa-password-marker");
    }
}
