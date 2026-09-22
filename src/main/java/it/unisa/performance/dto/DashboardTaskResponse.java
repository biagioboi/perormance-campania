package it.unisa.performance.dto;

import java.time.Instant;

public record DashboardTaskResponse(
    String jobId,
    String kind,
    String title,
    String status,
    String message,
    Integer progressCurrent,
    Integer progressTotal,
    Integer processedItems,
    Instant updatedAt) {}
