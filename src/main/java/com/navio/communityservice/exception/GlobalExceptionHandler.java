package com.navio.communityservice.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.*;
import java.time.Instant;
import java.util.*;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    @ExceptionHandler(GroupException.class)
    ResponseEntity<ErrorResponse> domain(GroupException ex, HttpServletRequest request) {
        return response(ex.getStatus(), ex.getMessage(), null, request);
    }
    @ExceptionHandler(ServletRequestBindingException.class)
    ResponseEntity<ErrorResponse> identity(ServletRequestBindingException ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "Request is invalid", null, request);
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> validation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getAllErrors().forEach(e -> fields.put(
                e instanceof FieldError f ? f.getField() : e.getObjectName(), e.getDefaultMessage()));
        return response(HttpStatus.BAD_REQUEST, "Validation failed", fields, request);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            HandlerMethodValidationException.class, IllegalArgumentException.class})
    ResponseEntity<ErrorResponse> malformed(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "Request is invalid", null, request);
    }
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> integrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        // Read constraint metadata only; never return database messages to clients.
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
                    && "groups_slug_key".equals(constraint.getConstraintName())) {
                return response(HttpStatus.CONFLICT, "A group with this slug already exists", null, request);
            }
            cause = cause.getCause();
        }
        return response(HttpStatus.UNPROCESSABLE_ENTITY, "Group data violates a storage constraint", null, request);
    }
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ErrorResponse> concurrent(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "Group is being changed; retry the request", null, request);
    }
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<ErrorResponse> oversized(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.PAYLOAD_TOO_LARGE, "Picture must be at most 5 MiB", null, request);
    }
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    ResponseEntity<ErrorResponse> missingFile(Exception ex, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, "A picture file is required", null, request);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Community failure operation={} path={} caller={} type={}", request.getMethod(),
                request.getRequestURI(), (request.getUserPrincipal() == null ? null : request.getUserPrincipal().getName()), ex.getClass().getSimpleName());
        return response(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", null, request);
    }
    private ResponseEntity<ErrorResponse> response(HttpStatus status, String message, Map<String, String> fields,
                                                  HttpServletRequest request) {
        log.warn("Community request failed operation={} path={} caller={} status={} reason={}", request.getMethod(),
                request.getRequestURI(), (request.getUserPrincipal() == null ? null : request.getUserPrincipal().getName()), status.value(), message);
        return ResponseEntity.status(status).body(new ErrorResponse(Instant.now(), status.value(), message,
                status.getReasonPhrase(), fields));
    }
}
