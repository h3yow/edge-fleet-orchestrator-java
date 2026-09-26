package com.github.h3yow.edgefleetorchestratorjava.support;

import com.github.h3yow.edgefleetorchestratorjava.domain.ApiException;
import com.github.h3yow.edgefleetorchestratorjava.web.ApiDtos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常映射。错误码稳定且语义可区分：
 * <ul>
 *   <li>NOT_FOUND / REVISION_NOT_FOUND：目标不存在（404）；</li>
 *   <li>FORBIDDEN_OWNER：跨归属方越权（403）；</li>
 *   <li>INCOMPATIBLE：版本/能力不在兼容范围（422）；</li>
 *   <li>DEVICE_ALREADY_EXISTS / INVALID_REQUEST：请求问题（400）。</li>
 * </ul>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiDtos.ErrorBody> handle(ApiException ex) {
        HttpStatus status = statusOf(ex.code());
        log.warn("api error | code={} | httpStatus={} | message={}", ex.code(), status, ex.getMessage());
        return ResponseEntity.status(status).body(new ApiDtos.ErrorBody(ex.code(), ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiDtos.ErrorBody> handleBadInput(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiDtos.ErrorBody("INVALID_REQUEST", ex.getMessage()));
    }

    private HttpStatus statusOf(String code) {
        return switch (code) {
            case "NOT_FOUND", "REVISION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "FORBIDDEN_OWNER" -> HttpStatus.FORBIDDEN;
            case "INCOMPATIBLE" -> HttpStatus.UNPROCESSABLE_ENTITY;
            case "DEVICE_ALREADY_EXISTS", "INVALID_REQUEST" -> HttpStatus.BAD_REQUEST;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
