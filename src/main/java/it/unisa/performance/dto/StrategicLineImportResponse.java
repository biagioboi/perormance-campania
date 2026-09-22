package it.unisa.performance.dto;

public record StrategicLineImportResponse(
    int pagesProcessed,
    int pagesImported,
    int pagesFailed,
    int itemsExtracted,
    int savedCount) {}
