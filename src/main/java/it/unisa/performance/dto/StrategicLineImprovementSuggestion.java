package it.unisa.performance.dto;

public record StrategicLineImprovementSuggestion(
    Long id,
    String code,
    String type,
    String originalText,
    String correctedText) {}
