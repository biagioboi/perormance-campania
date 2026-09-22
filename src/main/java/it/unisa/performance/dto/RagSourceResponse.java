package it.unisa.performance.dto;

import java.time.Instant;

public record RagSourceResponse(
    Long id,
    String fileName,
    String contentType,
    long fileSizeBytes,
    String status,
    String purpose,
    String statusMessage,
    Instant uploadedAt,
    Instant ingestedAt,
    int pageCount,
    int chunkCount) {}
