package it.unisa.performance.dto;

import it.unisa.performance.domain.StructureUnit;

public record StructureResponse(
    Long id,
    String code,
    String name,
    String area,
    double[] h,
    double avg) {

  public static StructureResponse from(StructureUnit structure) {
    return new StructureResponse(
        structure.getId(),
        structure.getCode(),
        structure.getName(),
        structure.getArea(),
        new double[] {
          structure.getPerformance2023(),
          structure.getPerformance2024(),
          structure.getPerformance2025()
        },
        structure.getAveragePerformance());
  }
}
