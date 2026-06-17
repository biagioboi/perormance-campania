package it.unisa.performance.dto;

import it.unisa.performance.domain.Objective;

public record ObjectiveResponse(
    Long objectiveId,
    String id,
    String line,
    String lineTitle,
    String area,
    String structure,
    String structureCode,
    String tier,
    String tierLabel,
    double avgPerf,
    double stretch,
    String title,
    String desc,
    String indicator,
    double baseTarget,
    double calibratedTarget,
    String unit,
    String direction,
    int weight) {

  public static ObjectiveResponse from(Objective objective) {
    return new ObjectiveResponse(
        objective.getId(),
        objective.getPublicId(),
        objective.getLineCode(),
        objective.getLineTitle(),
        objective.getArea(),
        objective.getStructureName(),
        objective.getStructureCode(),
        objective.getTier().name(),
        objective.getTier().getLabel(),
        objective.getAveragePerformance(),
        objective.getStretch(),
        objective.getTitle(),
        objective.getDescription(),
        objective.getIndicator(),
        objective.getBaseTarget(),
        objective.getCalibratedTarget(),
        objective.getUnit(),
        objective.getDirection().name(),
        objective.getWeight());
  }
}
