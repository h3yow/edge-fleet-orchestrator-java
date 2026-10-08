package com.github.h3yow.edgefleetorchestratorjava;

/** 同一个 batchId 已被创建（幂等键冲突，HTTP 409）。 */
public class BatchAlreadyExistsException extends RuntimeException {
    public BatchAlreadyExistsException(String batchId) {
        super("batch publish already exists: " + batchId);
    }
}
