package it.unisa.performance.dto;

public record ExtractedStrategicLineResponse(
    String externalKey,
    String parentExternalKey,
    String title,
    String type,
    String area,
    String priority,
    String desc) {}
