package com.flowpilot.todo;

import jakarta.persistence.OptimisticLockException;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(TodoNotFound.class)
    ProblemDetail notFound() { return problem(HttpStatus.NOT_FOUND, "TODO_NOT_FOUND", "할 일을 찾을 수 없습니다."); }

    @ExceptionHandler(BoardNotFound.class)
    ProblemDetail boardNotFound() { return problem(HttpStatus.NOT_FOUND, "BOARD_NOT_FOUND", "게시판을 찾을 수 없습니다."); }

    @ExceptionHandler(BoardPostNotFound.class)
    ProblemDetail boardPostNotFound() { return problem(HttpStatus.NOT_FOUND, "POST_NOT_FOUND", "게시물을 찾을 수 없습니다."); }

    @ExceptionHandler(PostAuthorRequired.class)
    ProblemDetail postAuthorRequired() { return problem(HttpStatus.FORBIDDEN, "POST_AUTHOR_REQUIRED", "작성자만 변경할 수 있습니다."); }

    @ExceptionHandler(BoardAccessDenied.class)
    ProblemDetail boardAccessDenied() { return problem(HttpStatus.FORBIDDEN, "ACCESS_DENIED", "요청 권한이 없습니다."); }

    @ExceptionHandler(BoardInactive.class)
    ProblemDetail boardInactive() { return problem(HttpStatus.CONFLICT, "BOARD_INACTIVE", "비활성 게시판에는 작성할 수 없습니다."); }

    @ExceptionHandler(BoardNameConflict.class)
    ProblemDetail boardNameConflict() { return problem(HttpStatus.CONFLICT, "BOARD_NAME_CONFLICT", "같은 이름의 게시판이 있습니다."); }

    @ExceptionHandler(ContentPolicyViolation.class)
    ProblemDetail contentPolicyViolation() {
        return problem(HttpStatus.BAD_REQUEST, "CONTENT_POLICY_VIOLATION", "허용되지 않은 콘텐츠입니다.");
    }

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

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ProblemDetail> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        HttpHeaders headers = new HttpHeaders();
        if (exception.getSupportedHttpMethods() != null) {
            headers.setAllow(exception.getSupportedHttpMethods());
        }
        return new ResponseEntity<>(
            problem(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다."),
            headers, HttpStatus.METHOD_NOT_ALLOWED);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> unsupportedMediaType(HttpMediaTypeNotSupportedException exception) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(exception.getSupportedMediaTypes());
        return new ResponseEntity<>(
            problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", "지원하지 않는 미디어 타입입니다."),
            headers, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessResourceException.class,
        QueryTimeoutException.class, CannotCreateTransactionException.class, CannotAcquireLockException.class,
        PessimisticLockingFailureException.class})
    ProblemDetail databaseUnavailable(Exception exception) {
        log.error("Database request failed; correlationId={}, exceptionType={}",
            MDC.get(CorrelationIdFilter.MDC_KEY), exception.getClass().getName());
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE", "서비스를 일시적으로 사용할 수 없습니다.");
    }

    @ExceptionHandler(JpaSystemException.class)
    ProblemDetail jpaSystem(JpaSystemException exception) {
        return databaseUnavailable(exception);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail resourceNotFound() {
        return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "요청한 자원을 찾을 수 없습니다.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception exception) {
        log.error("Unhandled request failure; correlationId={}, exceptionType={}",
            MDC.get(CorrelationIdFilter.MDC_KEY), exception.getClass().getName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "요청을 처리하지 못했습니다.");
    }

    private ValidationError validationError(FieldError error) {
        return new ValidationError(error.getField(), error.getDefaultMessage());
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setType(URI.create("about:blank"));
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            p.setInstance(URI.create(attributes.getRequest().getRequestURI()));
        }
        p.setProperty("code", code);
        p.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        return p;
    }

    private record ValidationError(String field, String reason) {}
}
