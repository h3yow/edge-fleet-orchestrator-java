package com.github.h3yow.edgefleetorchestratorjava.web;

import java.util.List;
import java.util.Map;

/**
 * HTTP 接口 DTO 集合（全部为不可变 record）。
 */
public final class ApiDtos {

    private ApiDtos() {
    }

    public record RangeSpec(String minFirmware, String maxFirmware, String requiredCapability) {
    }

    public record RegisterRequest(String deviceId, String owner, String firmware,
                                  List<String> capabilities, RangeSpec acceptedRange) {
    }

    public record DeviceView(String deviceId, String owner, String firmware,
                             List<String> capabilities, String liveness,
                             String lastAcceptedReportAt, long lastAcceptedSeq,
                             Long latestRevision, String latestState) {
    }

    public record ReportRequest(long seq, String reportTime) {
    }

    public record ReportView(String deviceId, long seq, String reportTime,
                             String disposition, String reason, String livenessAfter) {
    }

    public record DispatchRequest(String owner, Map<String, String> config, RangeSpec acceptedRange) {
    }

    public record DispatchView(String deviceId, long revision, String outcome,
                               String finalState, String detail, String decidedAt) {
    }

    public record ReviseRequest(String owner, RangeSpec acceptedRange) {
    }

    public record RevisionView(long revision, String owner, Map<String, String> config,
                               String state, String outcome, String detail,
                               String createdAt, String decidedAt) {
    }

    public record RevokeRequest(String owner) {
    }

    public record ErrorBody(String code, String message) {
    }
}
