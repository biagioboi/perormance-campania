package it.unisa.performance.dto;

import it.unisa.performance.domain.GenerationRun;
import java.time.Instant;
import java.util.List;

public record GenerationResponse(
    Long generationId,
    Instant createdAt,
    String mode,
    int nPerLine,
    double avgStretch,
    List<ObjectiveResponse> objectives) {

  public static GenerationResponse from(GenerationRun run) {
    var objectives = run.getObjectives().stream().map(ObjectiveResponse::from).toList();
    double avgStretch = objectives.stream()
        .flatMap(objective -> objective.assignments().stream())
        .mapToDouble(ObjectiveResponse.AssignmentResponse::stretch)
        .average()
        .orElse(0);
    return new GenerationResponse(
        run.getId(),
        run.getCreatedAt(),
        run.getMode(),
        run.getObjectivesPerLine(),
        Math.round(avgStretch * 10.0) / 10.0,
        objectives);
  }
}
