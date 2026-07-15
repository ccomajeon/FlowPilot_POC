package com.flowpilot.todo;

import jakarta.persistence.OptimisticLockException;
import java.net.URI;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(TodoNotFound.class)
    ProblemDetail notFound() { return problem(HttpStatus.NOT_FOUND, "TODO_NOT_FOUND", "할 일을 찾을 수 없습니다."); }

    @ExceptionHandler({VersionConflict.class, OptimisticLockException.class, OptimisticLockingFailureException.class})
    ProblemDetail conflict() { return problem(HttpStatus.CONFLICT, "VERSION_CONFLICT", "다른 요청이 먼저 변경했습니다."); }

    @ExceptionHandler(PreconditionRequired.class)
    ProblemDetail precondition() { return problem(HttpStatus.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED", "If-Match가 필요합니다."); }

    @ExceptionHandler({BadRequest.class, MethodArgumentNotValidException.class})
    ProblemDetail badRequest(Exception e) { return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "요청 값을 확인하세요."); }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("about:blank"));
        p.setProperty("code", code);
        return p;
    }
}
