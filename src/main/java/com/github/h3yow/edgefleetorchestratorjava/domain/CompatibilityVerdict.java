package com.github.h3yow.edgefleetorchestratorjava.domain;

/**
 * 能力/版本兼容判定结论。
 */
public record CompatibilityVerdict(boolean compatible, String gap) {
}
