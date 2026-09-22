package it.unisa.performance.dto;

public record StrategicLineImportJobResponse(
    String jobId,
    String status,
    String message,
    StrategicLineImportResponse summary) {

  public static StrategicLineImportJobResponse running(
      String jobId,
      String message,
      StrategicLineImportResponse summary) {
    return new StrategicLineImportJobResponse(jobId, "RUNNING", message, summary);
  }

  public static StrategicLineImportJobResponse completed(
      String jobId,
      String message,
      StrategicLineImportResponse summary) {
    return new StrategicLineImportJobResponse(jobId, "COMPLETED", message, summary);
  }

  public static StrategicLineImportJobResponse failed(
      String jobId,
      String message,
      StrategicLineImportResponse summary) {
    return new StrategicLineImportJobResponse(jobId, "FAILED", message, summary);
  }
}
