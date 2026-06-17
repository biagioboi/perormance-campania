package it.unisa.performance.dto;

import jakarta.validation.constraints.DecimalMin;

public record UpdateObjectiveBaseTargetRequest(
    @DecimalMin("0.0") double baseTarget) {}
