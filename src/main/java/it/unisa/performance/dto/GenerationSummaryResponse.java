package it.unisa.performance.dto;

import it.unisa.performance.domain.GenerationRun;
import java.time.Instant;

public record GenerationSummaryResponse(
    Long generationId,
    Instant createdAt,
    String mode,
    int nPerLine,
    int objectivesCount) {

  public static GenerationSummaryResponse from(GenerationRun run) {
    return new GenerationSummaryResponse(
        run.getId(),
        run.getCreatedAt(),
        run.getMode(),
        run.getObjectivesPerLine(),
        run.getObjectives().size());
  }
}
