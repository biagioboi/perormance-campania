package it.unisa.performance.dto;

public record GenerationJobResponse(
    String jobId,
    String status,
    String message,
    GenerationResponse generation) {

  public static GenerationJobResponse running(String jobId, String message) {
    return new GenerationJobResponse(jobId, "RUNNING", message, null);
  }

  public static GenerationJobResponse completed(String jobId, GenerationResponse generation) {
    return new GenerationJobResponse(jobId, "COMPLETED", "Simulazione completata", generation);
  }

  public static GenerationJobResponse failed(String jobId, String message) {
    return new GenerationJobResponse(jobId, "FAILED", message, null);
  }
}
