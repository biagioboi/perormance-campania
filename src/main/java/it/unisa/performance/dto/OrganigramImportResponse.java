package it.unisa.performance.dto;

public record OrganigramImportResponse(
    int pagesProcessed,
    int pagesImported,
    int pagesFailed,
    int savedCount,
    int baselineCount) {}
