package it.unisa.performance.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

public record UpdateObjectiveBaseTargetRequest(
    @Min(0) int assignmentIndex,
    @Min(0) int actionIndex,
    @DecimalMin("0.0") double baseTarget) {}
