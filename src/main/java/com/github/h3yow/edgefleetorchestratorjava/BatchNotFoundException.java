package com.github.h3yow.edgefleetorchestratorjava;

/** 指定的批量发布不存在（HTTP 404，与设备不存在的语义独立但可区分）。 */
public class BatchNotFoundException extends RuntimeException {
    public BatchNotFoundException(String batchId) {
        super("batch publish not found: " + batchId);
    }
}
