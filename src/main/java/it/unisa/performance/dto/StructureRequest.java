package it.unisa.performance.dto;

import it.unisa.performance.domain.StructureUnitType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StructureRequest(
    @NotBlank @Size(max = 32) String code,
    @NotBlank @Size(max = 512) String name,
    StructureUnitType type,
    Long parentId,
    @NotBlank @Size(max = 80) String area,
    @DecimalMin("0.0") @DecimalMax("100.0") double performance2023,
    @DecimalMin("0.0") @DecimalMax("100.0") double performance2024,
    @DecimalMin("0.0") @DecimalMax("100.0") double performance2025) {}
