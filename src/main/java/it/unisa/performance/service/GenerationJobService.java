package it.unisa.performance.service;

import it.unisa.performance.dto.GenerateObjectivesRequest;
import it.unisa.performance.dto.GenerationJobResponse;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class GenerationJobService {

  private final DashboardService dashboardService;
  private final Map<String, GenerationJobResponse> jobs = new ConcurrentHashMap<>();

  public GenerationJobService(DashboardService dashboardService) {
    this.dashboardService = dashboardService;
  }

  public GenerationJobResponse start(GenerateObjectivesRequest request) {
    var jobId = UUID.randomUUID().toString();
    jobs.put(jobId, GenerationJobResponse.running(jobId, "Simulazione avviata"));

    CompletableFuture.runAsync(() -> runJob(jobId, request));
    return jobs.get(jobId);
  }

  public GenerationJobResponse status(String jobId) {
    return jobs.getOrDefault(jobId, GenerationJobResponse.failed(jobId, "Job non trovato"));
  }

  private void runJob(String jobId, GenerateObjectivesRequest request) {
    try {
      jobs.put(jobId, GenerationJobResponse.running(jobId, "Generazione obiettivi in corso"));
      var run = dashboardService.generateObjectives(
          request,
          message -> jobs.put(jobId, GenerationJobResponse.running(jobId, message)));
      var generation = dashboardService.generation(run.getId())
          .orElseThrow(() -> new IllegalStateException("Simulazione generata ma non riletta dal database"));
      jobs.put(jobId, GenerationJobResponse.completed(jobId, generation));
    } catch (Exception exception) {
      jobs.put(jobId, GenerationJobResponse.failed(jobId, exception.getMessage()));
    }
  }
}
