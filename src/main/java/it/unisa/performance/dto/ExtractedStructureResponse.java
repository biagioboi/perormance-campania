package it.unisa.performance.dto;

public record ExtractedStructureResponse(
    String externalKey,
    String parentExternalKey,
    String code,
    String name,
    String type,
    String area) {}
