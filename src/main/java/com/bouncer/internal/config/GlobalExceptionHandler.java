package com.bouncer.internal.config;

import com.bouncer.internal.exception.IdempotencyConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyConflict(
            IdempotencyConflictException ex
    ) {
        log.warn("Idempotency conflict: key={} reason={}", ex.getIdempotencyKey(), ex.getReason());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(Map.of(
                        "status", 409,
                        "error", "Idempotency conflict",
                        "reason", ex.getReason().name(),
                        "message", ex.getMessage(),
                        "idempotency_key", ex.getIdempotencyKey(),
                        "timestamp", Instant.now().toString()
                ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(
            MethodArgumentNotValidException ex
    ) {
        List<Map<String, String>> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", fe.getDefaultMessage() != null
                                ? fe.getDefaultMessage()
                                : "invalid value"
                ))
                .toList();

        log.debug("Validation failed: {}", fieldErrors);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "status", 400,
                        "error", "Validation failed",
                        "fields", fieldErrors,
                        "timestamp", Instant.now().toString()
                ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericError(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "status", 500,
                        "error", "Internal server error",
                        "timestamp", Instant.now().toString()
                ));
    }
}
