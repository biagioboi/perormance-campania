package it.unisa.performance.service;

import it.unisa.performance.dto.DashboardTaskResponse;
import it.unisa.performance.dto.OrganigramImportJobResponse;
import it.unisa.performance.dto.OrganigramImportResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class OrganigramImportJobService {

  private final PdfExtractionService pdfExtractionService;
  private final DashboardService dashboardService;
  private final Map<String, OrganigramImportJobResponse> jobs = new ConcurrentHashMap<>();
  private final Map<String, Instant> updatedAt = new ConcurrentHashMap<>();

  public OrganigramImportJobService(
      PdfExtractionService pdfExtractionService,
      DashboardService dashboardService) {
    this.pdfExtractionService = pdfExtractionService;
    this.dashboardService = dashboardService;
  }

  public OrganigramImportJobResponse start(MultipartFile file) throws IOException {
    var jobId = UUID.randomUUID().toString();
    var snapshot = new InMemoryMultipartFile(file.getOriginalFilename(), file.getBytes());
    storeJob(jobId, OrganigramImportJobResponse.running(
        jobId,
        "Import organigramma avviato",
        emptySummary()));

    CompletableFuture.runAsync(() -> runJob(jobId, snapshot));
    return jobs.get(jobId);
  }

  public OrganigramImportJobResponse status(String jobId) {
    return jobs.getOrDefault(
        jobId,
        OrganigramImportJobResponse.failed(jobId, "Job organigramma non trovato", emptySummary()));
  }

  public List<DashboardTaskResponse> tasks() {
    return jobs.entrySet().stream()
        .map(entry -> toTask(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparing(DashboardTaskResponse::updatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
        .limit(12)
        .toList();
  }

  private void storeJob(String jobId, OrganigramImportJobResponse response) {
    jobs.put(jobId, response);
    updatedAt.put(jobId, Instant.now());
  }

  private DashboardTaskResponse toTask(String jobId, OrganigramImportJobResponse response) {
    var summary = response.summary();
    Integer current = summary != null ? summary.pagesImported() + summary.pagesFailed() : null;
    Integer total = summary != null && summary.pagesProcessed() > 0 ? summary.pagesProcessed() : null;
    Integer processedItems = summary != null ? summary.savedCount() : null;
    return new DashboardTaskResponse(
        jobId,
        "organigram",
        "Import organigramma",
        response.status(),
        response.message(),
        current,
        total,
        processedItems,
        updatedAt.get(jobId));
  }

  private void runJob(String jobId, MultipartFile file) {
    final int[] pagesImported = {0};
    final int[] pagesFailed = {0};
    final int[] savedCount = {0};
    final int[] baselineCount = {0};
    final int[] totalPages = {0};

    try {
      storeJob(jobId, OrganigramImportJobResponse.running(
          jobId,
          "Preparazione pagine PDF in corso",
          emptySummary()));

      var summary = pdfExtractionService.processOrganigramStructuresByPage(file, result -> {
        totalPages[0] = result.totalPages();
        if (result.imported()) {
          var imported = dashboardService.importExtractedStructures(result.structures());
          pagesImported[0]++;
          savedCount[0] += imported.savedCount();
          baselineCount[0] += imported.baselineCount();
          storeJob(jobId, OrganigramImportJobResponse.running(
              jobId,
              "Pagina " + result.pageNumber() + "/" + result.totalPages() + " importata",
              new OrganigramImportResponse(
                  result.totalPages(),
                  pagesImported[0],
                  pagesFailed[0],
                  savedCount[0],
                  baselineCount[0])));
        } else {
          pagesFailed[0]++;
          storeJob(jobId, OrganigramImportJobResponse.running(
              jobId,
              "Pagina " + result.pageNumber() + "/" + result.totalPages() + " senza estrazione utile",
              new OrganigramImportResponse(
                  result.totalPages(),
                  pagesImported[0],
                  pagesFailed[0],
                  savedCount[0],
                  baselineCount[0])));
        }
      });

      var finalSummary = new OrganigramImportResponse(
          summary.pagesProcessed(),
          pagesImported[0],
          pagesFailed[0],
          savedCount[0],
          baselineCount[0]);
      storeJob(jobId, OrganigramImportJobResponse.completed(
          jobId,
          "Import organigramma completato",
          finalSummary));
    } catch (Exception exception) {
      storeJob(jobId, OrganigramImportJobResponse.failed(
          jobId,
          exception.getMessage(),
          new OrganigramImportResponse(
              totalPages[0],
              pagesImported[0],
              pagesFailed[0],
              savedCount[0],
              baselineCount[0])));
    }
  }

  private OrganigramImportResponse emptySummary() {
    return new OrganigramImportResponse(0, 0, 0, 0, 0);
  }

  private static final class InMemoryMultipartFile implements MultipartFile {
    private final String originalFilename;
    private final byte[] content;

    private InMemoryMultipartFile(String originalFilename, byte[] content) {
      this.originalFilename = originalFilename == null ? "organigramma.pdf" : originalFilename;
      this.content = content == null ? new byte[0] : content;
    }

    @Override
    public String getName() {
      return "data";
    }

    @Override
    public String getOriginalFilename() {
      return originalFilename;
    }

    @Override
    public String getContentType() {
      return "application/pdf";
    }

    @Override
    public boolean isEmpty() {
      return content.length == 0;
    }

    @Override
    public long getSize() {
      return content.length;
    }

    @Override
    public byte[] getBytes() {
      return content.clone();
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(java.io.File dest) throws IOException {
      Files.write(dest.toPath(), content);
    }

    @Override
    public void transferTo(Path dest) throws IOException {
      Files.write(dest, content);
    }
  }
}
