package com.github.h3yow.edgefleetorchestratorjava.domain;

/**
 * 业务异常：携带稳定错误码，可与“目标不存在”等情形区分。
 */
public class ApiException extends RuntimeException {

    private final String code;

    public ApiException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
