package it.unisa.performance.dto;

import it.unisa.performance.domain.StrategicLine;

public record StrategicLineResponse(
    Long id,
    String code,
    String title,
    String area,
    String priority,
    String desc) {

  public static StrategicLineResponse from(StrategicLine line) {
    return new StrategicLineResponse(
        line.getId(),
        line.getCode(),
        line.getTitle(),
        line.getArea(),
        line.getPriority().name(),
        line.getDescription());
  }
}
