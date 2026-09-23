package it.unisa.performance.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record ReviewObjectiveActionRequest(
    @Min(0) int assignmentIndex,
    @Min(0) int actionIndex,
    Boolean approved,
    @Min(0) @Max(100) Integer score) {}
