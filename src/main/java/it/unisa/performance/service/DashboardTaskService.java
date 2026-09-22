package it.unisa.performance.service;

import it.unisa.performance.dto.DashboardTaskResponse;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class DashboardTaskService {

  private final GenerationJobService generationJobService;
  private final OrganigramImportJobService organigramImportJobService;
  private final StrategicLineImportJobService strategicLineImportJobService;
  private final RagImportJobService ragImportJobService;

  public DashboardTaskService(
      GenerationJobService generationJobService,
      OrganigramImportJobService organigramImportJobService,
      StrategicLineImportJobService strategicLineImportJobService,
      RagImportJobService ragImportJobService) {
    this.generationJobService = generationJobService;
    this.organigramImportJobService = organigramImportJobService;
    this.strategicLineImportJobService = strategicLineImportJobService;
    this.ragImportJobService = ragImportJobService;
  }

  public List<DashboardTaskResponse> tasks() {
    return Stream.of(
            generationJobService.tasks().stream(),
            organigramImportJobService.tasks().stream(),
            strategicLineImportJobService.tasks().stream(),
            ragImportJobService.tasks().stream())
        .flatMap(stream -> stream)
        .sorted(Comparator.comparing(DashboardTaskResponse::updatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
        .limit(12)
        .toList();
  }
}
