package it.unisa.performance.dto;

public record RagImportJobResponse(
    String jobId,
    String status,
    String message,
    Long sourceId,
    RagImportResponse summary) {

  public static RagImportJobResponse running(
      String jobId, String message, Long sourceId, RagImportResponse summary) {
    return new RagImportJobResponse(jobId, "RUNNING", message, sourceId, summary);
  }

  public static RagImportJobResponse completed(
      String jobId, String message, Long sourceId, RagImportResponse summary) {
    return new RagImportJobResponse(jobId, "COMPLETED", message, sourceId, summary);
  }

  public static RagImportJobResponse failed(
      String jobId, String message, Long sourceId, RagImportResponse summary) {
    return new RagImportJobResponse(jobId, "FAILED", message, sourceId, summary);
  }
}
