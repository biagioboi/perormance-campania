package it.unisa.performance.service;

import it.unisa.performance.dto.DashboardTaskResponse;
import it.unisa.performance.dto.StrategicLineImportJobResponse;
import it.unisa.performance.dto.StrategicLineImportResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class StrategicLineImportJobService {

  private final PdfExtractionService pdfExtractionService;
  private final DashboardService dashboardService;
  private final Map<String, StrategicLineImportJobResponse> jobs = new ConcurrentHashMap<>();
  private final Map<String, Instant> updatedAt = new ConcurrentHashMap<>();

  public StrategicLineImportJobService(
      PdfExtractionService pdfExtractionService,
      DashboardService dashboardService) {
    this.pdfExtractionService = pdfExtractionService;
    this.dashboardService = dashboardService;
  }

  public StrategicLineImportJobResponse start(MultipartFile file) throws IOException {
    var jobId = UUID.randomUUID().toString();
    var snapshot = new InMemoryMultipartFile(file.getOriginalFilename(), file.getBytes());
    storeJob(jobId, StrategicLineImportJobResponse.running(
        jobId,
        "Import linee strategiche avviato",
        emptySummary()));

    CompletableFuture.runAsync(() -> runJob(jobId, snapshot));
    return jobs.get(jobId);
  }

  public StrategicLineImportJobResponse status(String jobId) {
    return jobs.getOrDefault(
        jobId,
        StrategicLineImportJobResponse.failed(jobId, "Job linee strategiche non trovato", emptySummary()));
  }

  public List<DashboardTaskResponse> tasks() {
    return jobs.entrySet().stream()
        .map(entry -> toTask(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparing(DashboardTaskResponse::updatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
        .limit(12)
        .toList();
  }

  private void storeJob(String jobId, StrategicLineImportJobResponse response) {
    jobs.put(jobId, response);
    updatedAt.put(jobId, Instant.now());
  }

  private DashboardTaskResponse toTask(String jobId, StrategicLineImportJobResponse response) {
    var summary = response.summary();
    Integer current = summary != null ? summary.pagesImported() + summary.pagesFailed() : null;
    Integer total = summary != null && summary.pagesProcessed() > 0 ? summary.pagesProcessed() : null;
    Integer processedItems = summary != null ? summary.savedCount() : null;
    return new DashboardTaskResponse(
        jobId,
        "strategic-lines",
        "Import linee strategiche",
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
    final int[] itemsExtracted = {0};
    final int[] savedCount = {0};
    final int[] totalPages = {0};
    var relation = new LinkedHashMap<String, Long>();

    try {
      storeJob(jobId, StrategicLineImportJobResponse.running(
          jobId,
          "Preparazione pagine PDF in corso",
          emptySummary()));

      pdfExtractionService.processStrategicLinesByPage(file, result -> {
        totalPages[0] = result.totalPages();
        if (!result.items().isEmpty()) {
          pagesImported[0]++;
          itemsExtracted[0] += result.items().size();
          savedCount[0] += dashboardService.importExtractedStrategicLines(result.items(), relation);
          storeJob(jobId, StrategicLineImportJobResponse.running(
              jobId,
              "Pagina " + result.pageNumber() + "/" + result.totalPages() + " importata",
              new StrategicLineImportResponse(
                  result.totalPages(),
                  pagesImported[0],
                  pagesFailed[0],
                  itemsExtracted[0],
                  savedCount[0])));
        } else {
          pagesFailed[0]++;
          storeJob(jobId, StrategicLineImportJobResponse.running(
              jobId,
              "Pagina " + result.pageNumber() + "/" + result.totalPages() + " senza estrazione utile",
              new StrategicLineImportResponse(
                  result.totalPages(),
                  pagesImported[0],
                  pagesFailed[0],
                  itemsExtracted[0],
                  savedCount[0])));
        }
      });

      var finalSummary = new StrategicLineImportResponse(
          totalPages[0],
          pagesImported[0],
          pagesFailed[0],
          itemsExtracted[0],
          savedCount[0]);
      storeJob(jobId, StrategicLineImportJobResponse.completed(
          jobId,
          "Import linee strategiche completato",
          finalSummary));
    } catch (Exception exception) {
      storeJob(jobId, StrategicLineImportJobResponse.failed(
          jobId,
          exception.getMessage(),
          new StrategicLineImportResponse(
              totalPages[0],
              pagesImported[0],
              pagesFailed[0],
              itemsExtracted[0],
              savedCount[0])));
    }
  }

  private StrategicLineImportResponse emptySummary() {
    return new StrategicLineImportResponse(0, 0, 0, 0, 0);
  }

  private static final class InMemoryMultipartFile implements MultipartFile {
    private final String originalFilename;
    private final byte[] content;

    private InMemoryMultipartFile(String originalFilename, byte[] content) {
      this.originalFilename = originalFilename == null ? "linee-strategiche.pdf" : originalFilename;
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
