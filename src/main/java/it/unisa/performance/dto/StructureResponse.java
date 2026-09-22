package it.unisa.performance.dto;

import it.unisa.performance.domain.StructureUnit;
import it.unisa.performance.domain.StructureUnitType;

public record StructureResponse(
    Long id,
    String code,
    String name,
    String type,
    Long parentId,
    String parentCode,
    String parentName,
    Long sectorId,
    String sectorCode,
    String sectorName,
    String dgCode,
    String dgName,
    String area,
    double[] h,
    double avg) {

  public static StructureResponse from(StructureUnit structure) {
    var dg = direzioneGeneraleOf(structure);
    var sector = sectorOf(structure);
    var parent = structure.getParent();
    return new StructureResponse(
        structure.getId(),
        structure.getCode(),
        structure.getName(),
        structure.getType().name(),
        parent != null ? parent.getId() : null,
        parent != null ? parent.getCode() : null,
        parent != null ? parent.getName() : null,
        sector != null ? sector.getId() : null,
        sector != null ? sector.getCode() : null,
        sector != null ? sector.getName() : null,
        dg.getCode(),
        dg.getName(),
        structure.getArea(),
        new double[] {
          structure.getPerformance2023(),
          structure.getPerformance2024(),
          structure.getPerformance2025()
        },
        structure.getAveragePerformance());
  }

  private static StructureUnit direzioneGeneraleOf(StructureUnit structure) {
    var current = structure;
    while (current.getParent() != null) {
      current = current.getParent();
    }
    return current;
  }

  private static StructureUnit sectorOf(StructureUnit structure) {
    if (structure.getType() == StructureUnitType.settore) {
      return structure;
    }
    var parent = structure.getParent();
    if (parent != null && parent.getType() == StructureUnitType.settore) {
      return parent;
    }
    return null;
  }
}
