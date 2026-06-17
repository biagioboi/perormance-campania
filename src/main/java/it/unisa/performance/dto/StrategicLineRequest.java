package it.unisa.performance.dto;

import it.unisa.performance.domain.Priority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StrategicLineRequest(
    @NotBlank @Size(max = 32) String code,
    @NotBlank String title,
    @NotBlank @Size(max = 80) String area,
    @NotNull Priority priority,
    @NotBlank @Size(max = 1200) String desc) {}
