package it.unisa.performance.dto;

public record OrganigramImportJobResponse(
    String jobId,
    String status,
    String message,
    OrganigramImportResponse summary) {

  public static OrganigramImportJobResponse running(
      String jobId,
      String message,
      OrganigramImportResponse summary) {
    return new OrganigramImportJobResponse(jobId, "RUNNING", message, summary);
  }

  public static OrganigramImportJobResponse completed(
      String jobId,
      String message,
      OrganigramImportResponse summary) {
    return new OrganigramImportJobResponse(jobId, "COMPLETED", message, summary);
  }

  public static OrganigramImportJobResponse failed(
      String jobId,
      String message,
      OrganigramImportResponse summary) {
    return new OrganigramImportJobResponse(jobId, "FAILED", message, summary);
  }
}
