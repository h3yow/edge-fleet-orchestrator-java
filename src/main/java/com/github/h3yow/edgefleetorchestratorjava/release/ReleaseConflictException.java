package com.github.h3yow.edgefleetorchestratorjava.release;

/** 同一 releaseId 被以不同的载荷/兼容要求重复提交（幂等键冲突）。 */
public class ReleaseConflictException extends RuntimeException {
    public ReleaseConflictException(String releaseId) {
        super("release " + releaseId + " already exists with a different payload/requirements");
    }
}
