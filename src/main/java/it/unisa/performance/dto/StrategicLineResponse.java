package it.unisa.performance.dto;

import it.unisa.performance.domain.StrategicLine;

public record StrategicLineResponse(
    Long id,
    String code,
    String title,
    String type,
    Long parentId,
    String parentCode,
    String parentTitle,
    String categoryCode,
    String categoryTitle,
    String area,
    String priority,
    String desc) {

  public static StrategicLineResponse from(StrategicLine line) {
    var parent = line.getParent();
    var category = parent != null ? parent : line;
    return new StrategicLineResponse(
        line.getId(),
        line.getCode(),
        line.getTitle(),
        line.getType().name(),
        parent != null ? parent.getId() : null,
        parent != null ? parent.getCode() : null,
        parent != null ? parent.getTitle() : null,
        category.getCode(),
        category.getTitle(),
        line.getArea(),
        line.getPriority() != null ? line.getPriority().name() : null,
        line.getDescription());
  }
}
