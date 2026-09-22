package it.unisa.performance.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record GenerateObjectivesRequest(
    @NotBlank String mode,
    @Min(1) @Max(3) int nPerLine,
    @Min(0) @Max(50) double stretchEcc,
    @Min(0) @Max(50) double stretchBuo,
    @Min(0) @Max(50) double stretchAde,
    @Min(0) @Max(50) double stretchRec,
    @Min(1) @Max(8) int minActionsPerAssignment,
    @Min(1) @Max(8) int maxActionsPerAssignment,
    @Min(0) @Max(100) int multiStructureSharePercent,
    @Min(1) @Max(100) int actionWeightMin,
    @Min(1) @Max(100) int actionWeightMax,
    @Min(0) @Max(100) int publicValueSharePercent,
    String preferredMissions) {}
