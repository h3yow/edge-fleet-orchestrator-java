package com.github.h3yow.edgefleetorchestratorjava.api;

import com.github.h3yow.edgefleetorchestratorjava.DeviceNotFoundException;
import com.github.h3yow.edgefleetorchestratorjava.OwnerMismatchException;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将各类拒绝映射为可区分的 HTTP 结论：404 不存在 / 403 越权 / 409 冲突 / 400 参数。 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(DeviceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(DeviceNotFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", ex.getMessage());
    }

    @ExceptionHandler(OwnerMismatchException.class)
    public ResponseEntity<Map<String, Object>> ownerMismatch(OwnerMismatchException ex) {
        return build(HttpStatus.FORBIDDEN, "OWNER_MISMATCH", ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> conflict(IllegalStateException ex) {
        return build(HttpStatus.CONFLICT, "DEVICE_ALREADY_REGISTERED", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }

    private ResponseEntity<Map<String, Object>> build(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", error,
                "message", message,
                "timestamp", Instant.now().toString()));
    }
}
