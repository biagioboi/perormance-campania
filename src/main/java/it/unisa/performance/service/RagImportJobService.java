package it.unisa.performance.service;

import it.unisa.performance.domain.RagSource;
import it.unisa.performance.domain.RagSourcePurpose;
import it.unisa.performance.domain.RagSourceStatus;
import it.unisa.performance.dto.DashboardTaskResponse;
import it.unisa.performance.dto.RagImportJobResponse;
import it.unisa.performance.dto.RagImportResponse;
import it.unisa.performance.repository.RagSourceRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Mirrors {@link StrategicLineImportJobService}'s async job-with-polling pattern for RAG source uploads. */
@Service
public class RagImportJobService {

  private final RagIngestionService ragIngestionService;
  private final RagSourceRepository ragSourceRepository;
  private final Map<String, RagImportJobResponse> jobs = new ConcurrentHashMap<>();
  private final Map<String, Instant> updatedAt = new ConcurrentHashMap<>();

  public RagImportJobService(RagIngestionService ragIngestionService, RagSourceRepository ragSourceRepository) {
    this.ragIngestionService = ragIngestionService;
    this.ragSourceRepository = ragSourceRepository;
  }

  public RagImportJobResponse start(MultipartFile file, RagSourcePurpose purpose) throws IOException {
    var jobId = UUID.randomUUID().toString();
    var snapshot = new InMemoryMultipartFile(
        file.getOriginalFilename(), file.getContentType(), file.getBytes());

    var source = new RagSource();
    source.setFileName(snapshot.getOriginalFilename());
    source.setContentType(snapshot.getContentType());
    source.setFileSizeBytes(snapshot.getSize());
    source.setStatus(RagSourceStatus.PENDING);
    source.setPurpose(purpose);
    source.setUploadedAt(Instant.now());
    source = ragSourceRepository.save(source);

    storeJob(jobId, RagImportJobResponse.running(
        jobId, "Import fonte RAG avviato", source.getId(), emptySummary()));

    var sourceId = source.getId();
    CompletableFuture.runAsync(() -> runJob(jobId, sourceId, snapshot, purpose));
    return jobs.get(jobId);
  }

  public RagImportJobResponse status(String jobId) {
    return jobs.getOrDefault(
        jobId, RagImportJobResponse.failed(jobId, "Job import RAG non trovato", null, emptySummary()));
  }

  public List<DashboardTaskResponse> tasks() {
    return jobs.entrySet().stream()
        .map(entry -> toTask(entry.getKey(), entry.getValue()))
        .sorted(Comparator.comparing(DashboardTaskResponse::updatedAt, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
        .limit(12)
        .toList();
  }

  private void storeJob(String jobId, RagImportJobResponse response) {
    jobs.put(jobId, response);
    updatedAt.put(jobId, Instant.now());
  }

  private DashboardTaskResponse toTask(String jobId, RagImportJobResponse response) {
    var summary = response.summary();
    Integer processedItems = summary != null ? summary.chunkCount() : null;
    return new DashboardTaskResponse(
        jobId,
        "rag-source",
        "Import fonte RAG",
        response.status(),
        response.message(),
        null,
        null,
        processedItems,
        updatedAt.get(jobId));
  }

  private void runJob(String jobId, Long sourceId, MultipartFile file, RagSourcePurpose purpose) {
    try {
      updateSourceStatus(sourceId, RagSourceStatus.INGESTING, "In elaborazione");
      storeJob(jobId, RagImportJobResponse.running(
          jobId, "Estrazione ed embedding in corso", sourceId, emptySummary()));

      var result = ragIngestionService.ingest(
          sourceId, file.getOriginalFilename(), file.getContentType(), file, purpose.name());
      var summary = new RagImportResponse(result.pageCount(), result.chunkCount());

      var source = ragSourceRepository.findById(sourceId).orElseThrow();
      source.setStatus(RagSourceStatus.READY);
      source.setStatusMessage(null);
      source.setIngestedAt(Instant.now());
      source.setPageCount(result.pageCount());
      source.setChunkCount(result.chunkCount());
      ragSourceRepository.save(source);

      storeJob(jobId, RagImportJobResponse.completed(jobId, "Import fonte RAG completato", sourceId, summary));
    } catch (Exception exception) {
      updateSourceStatus(sourceId, RagSourceStatus.FAILED, exception.getMessage());
      storeJob(jobId, RagImportJobResponse.failed(jobId, exception.getMessage(), sourceId, emptySummary()));
    }
  }

  private void updateSourceStatus(Long sourceId, RagSourceStatus status, String message) {
    ragSourceRepository.findById(sourceId).ifPresent(source -> {
      source.setStatus(status);
      source.setStatusMessage(message);
      ragSourceRepository.save(source);
    });
  }

  private RagImportResponse emptySummary() {
    return new RagImportResponse(0, 0);
  }

  private static final class InMemoryMultipartFile implements MultipartFile {
    private final String originalFilename;
    private final String contentType;
    private final byte[] content;

    private InMemoryMultipartFile(String originalFilename, String contentType, byte[] content) {
      this.originalFilename = originalFilename == null ? "fonte-rag" : originalFilename;
      this.contentType = contentType;
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
      return contentType;
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
