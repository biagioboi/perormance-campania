package it.unisa.performance.service;

import it.unisa.performance.dto.DashboardTaskResponse;
import it.unisa.performance.dto.GenerateObjectivesRequest;
import it.unisa.performance.dto.GenerationJobResponse;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class GenerationJobService {

  private final DashboardService dashboardService;
  private final OllamaObjectiveService ollamaObjectiveService;
  private final Map<String, GenerationJobResponse> jobs = new ConcurrentHashMap<>();
  private final Map<String, Instant> updatedAt = new ConcurrentHashMap<>();

  public GenerationJobService(DashboardService dashboardService, OllamaObjectiveService ollamaObjectiveService) {
    this.dashboardService = dashboardService;
    this.ollamaObjectiveService = ollamaObjectiveService;
  }

  public GenerationJobResponse start(GenerateObjectivesRequest request) {
    var jobId = UUID.randomUUID().toString();
    storeJob(jobId, GenerationJobResponse.running(jobId, "Simulazione avviata"));

    CompletableFuture.runAsync(() -> runJob(jobId, request));
    return jobs.get(jobId);
  }

  public GenerationJobResponse status(String jobId) {
    return jobs.getOrDefault(jobId, GenerationJobResponse.failed(jobId, "Job non trovato"));
  }

  public List<DashboardTaskResponse> tasks() {
    return jobs.entrySet().stream()
        .map(entry -> toTask(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparing(DashboardTaskResponse::updatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
        .limit(12)
        .toList();
  }

  private void storeJob(String jobId, GenerationJobResponse response) {
    jobs.put(jobId, response);
    updatedAt.put(jobId, Instant.now());
  }

  private DashboardTaskResponse toTask(String jobId, GenerationJobResponse response) {
    var generation = response.generation();
    Integer processedItems = generation != null && generation.objectives() != null
        ? generation.objectives().size()
        : null;
    return new DashboardTaskResponse(
        jobId,
        "generation",
        "Generazione obiettivi",
        response.status(),
        response.message(),
        null,
        null,
        processedItems,
        updatedAt.get(jobId));
  }

  // Each strategic line is persisted to the database as soon as it's generated (instead of holding
  // everything in memory until the whole run finishes) — a slow or interrupted run keeps whatever
  // progress it already made, visible immediately via the normal generations endpoints.
  private void runJob(String jobId, GenerateObjectivesRequest request) {
    try {
      storeJob(jobId, GenerationJobResponse.running(jobId, "Generazione obiettivi in corso"));
      var run = dashboardService.startGenerationRun(request);
      var lines = dashboardService.generationLineTargets();
      var aiMode = "ai".equalsIgnoreCase(request.mode());
      var totalSaved = new int[] {0};

      if (aiMode) {
        var structures = dashboardService.generationStructureTargets();
        ollamaObjectiveService.generateAssigned(
            lines,
            structures,
            request,
            message -> storeJob(jobId, GenerationJobResponse.running(jobId, message)),
            (line, assignedForLine) -> {
              var added = dashboardService.appendAssignedObjectives(run.getId(), line, assignedForLine, request);
              totalSaved[0] += added;
              storeJob(jobId, GenerationJobResponse.running(
                  jobId, "Linea " + line.getCode() + ": +" + added + " obiettivi (totale " + totalSaved[0] + ")"));
            });
      } else {
        for (var i = 0; i < lines.size(); i++) {
          var line = lines.get(i);
          storeJob(jobId, GenerationJobResponse.running(
              jobId, "Linea " + (i + 1) + "/" + lines.size() + " (" + line.getCode() + ")"));
          var added = dashboardService.appendTemplateObjectivesForLine(run.getId(), line, request);
          totalSaved[0] += added;
          storeJob(jobId, GenerationJobResponse.running(
              jobId, "Linea " + line.getCode() + ": +" + added + " obiettivi (totale " + totalSaved[0] + ")"));
        }
      }

      var generation = dashboardService.generation(run.getId())
          .orElseThrow(() -> new IllegalStateException("Simulazione generata ma non riletta dal database"));
      if (generation.objectives().isEmpty()) {
        storeJob(jobId, GenerationJobResponse.failed(jobId, "Nessun obiettivo generato: nessuna linea ha prodotto risultati validi"));
        return;
      }
      storeJob(jobId, GenerationJobResponse.completed(jobId, generation));
    } catch (Exception exception) {
      storeJob(jobId, GenerationJobResponse.failed(jobId, exception.getMessage()));
    }
  }
}
