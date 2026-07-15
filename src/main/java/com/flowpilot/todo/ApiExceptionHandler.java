package com.flowpilot.todo;

import jakarta.persistence.OptimisticLockException;
import java.net.URI;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(TodoNotFound.class)
    ProblemDetail notFound() { return problem(HttpStatus.NOT_FOUND, "TODO_NOT_FOUND", "할 일을 찾을 수 없습니다."); }

    @ExceptionHandler({VersionConflict.class, OptimisticLockException.class, OptimisticLockingFailureException.class})
    ProblemDetail conflict() { return problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "다른 요청이 먼저 변경했습니다."); }

    @ExceptionHandler(PreconditionRequired.class)
    ProblemDetail precondition() { return problem(HttpStatus.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED", "If-Match가 필요합니다."); }

    @ExceptionHandler({BadRequest.class, HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail badRequest(Exception e) { return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값을 확인하세요."); }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validation(MethodArgumentNotValidException e) {
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "하나 이상의 필드를 확인하세요.");
        List<ValidationError> errors = e.getBindingResult().getFieldErrors().stream()
            .map(this::validationError).toList();
        p.setProperty("errors", errors);
        return p;
    }

    private ValidationError validationError(FieldError error) {
        return new ValidationError(error.getField(), error.getDefaultMessage());
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("about:blank"));
        p.setProperty("code", code);
        p.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        return p;
    }

    private record ValidationError(String field, String reason) {}
}
