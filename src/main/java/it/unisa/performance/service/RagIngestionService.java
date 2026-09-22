package it.unisa.performance.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Ingests an uploaded RAG source (PDF or plain text) into Qdrant: extract text per page, chunk it,
 * embed each chunk (batched), then upsert. Page granularity mirrors how the other PDF pipelines
 * already treat the page as the natural unit ({@code processStrategicLinesByPage}, organigram
 * per-page extraction) — a fixed-size/overlap split only kicks in as a fallback for oversized pages,
 * so a single "topic" isn't spread across unrelated chunking strategies.
 */
@Service
public class RagIngestionService {

  private final PdfExtractionService pdfExtractionService;
  private final OllamaEmbeddingService embeddingService;
  private final QdrantClientService qdrantClientService;
  private final int chunkSizeChars;
  private final int chunkOverlapChars;

  public RagIngestionService(
      PdfExtractionService pdfExtractionService,
      OllamaEmbeddingService embeddingService,
      QdrantClientService qdrantClientService,
      @Value("${rag.chunk-size-chars}") int chunkSizeChars,
      @Value("${rag.chunk-overlap-chars}") int chunkOverlapChars) {
    this.pdfExtractionService = pdfExtractionService;
    this.embeddingService = embeddingService;
    this.qdrantClientService = qdrantClientService;
    this.chunkSizeChars = chunkSizeChars;
    this.chunkOverlapChars = chunkOverlapChars;
  }

  public record IngestionResult(int pageCount, int chunkCount) {}

  public IngestionResult ingest(
      long sourceId, String sourceName, String contentType, MultipartFile file, String purpose)
      throws IOException {
    var pages = extractPages(contentType, file);
    var chunks = new ArrayList<ChunkText>();
    for (var page : pages) {
      var pieces = splitIfNeeded(page.text());
      for (var i = 0; i < pieces.size(); i++) {
        chunks.add(new ChunkText(page.pageNumber(), i, pieces.get(i)));
      }
    }
    if (chunks.isEmpty()) {
      return new IngestionResult(pages.size(), 0);
    }

    var texts = chunks.stream().map(ChunkText::text).toList();
    var vectors = embeddingService.embed(texts);
    var points = new ArrayList<QdrantClientService.Point>(chunks.size());
    for (var i = 0; i < chunks.size(); i++) {
      var chunk = chunks.get(i);
      var payload = new LinkedHashMap<String, Object>();
      payload.put("source_id", sourceId);
      payload.put("source_name", sourceName);
      payload.put("chunk_index", chunk.chunkIndex());
      payload.put("page_number", chunk.pageNumber());
      payload.put("text", chunk.text());
      payload.put("purpose", purpose);
      points.add(QdrantClientService.Point.of(vectors.get(i), payload));
    }
    qdrantClientService.upsert(points);
    return new IngestionResult(pages.size(), chunks.size());
  }

  private List<PageText> extractPages(String contentType, MultipartFile file) throws IOException {
    var fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
    if ("application/pdf".equals(contentType) || fileName.endsWith(".pdf")) {
      return pdfExtractionService.extractSortedTextPages(file).stream()
          .map(page -> new PageText(page.pageNumber(), page.text()))
          .toList();
    }
    if (isPlainText(contentType, fileName)) {
      return List.of(new PageText(1, new String(file.getBytes(), StandardCharsets.UTF_8)));
    }
    throw new IllegalArgumentException(
        "Formato non supportato: " + contentType + ". Sono supportati solo PDF e file di testo (.txt).");
  }

  private boolean isPlainText(String contentType, String lowerCaseFileName) {
    return (contentType != null && contentType.startsWith("text/")) || lowerCaseFileName.endsWith(".txt");
  }

  private List<String> splitIfNeeded(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    var normalized = text.strip();
    if (normalized.length() <= chunkSizeChars) {
      return List.of(normalized);
    }
    var pieces = new ArrayList<String>();
    var start = 0;
    while (start < normalized.length()) {
      var end = Math.min(start + chunkSizeChars, normalized.length());
      pieces.add(normalized.substring(start, end));
      if (end == normalized.length()) {
        break;
      }
      var nextStart = end - chunkOverlapChars;
      start = nextStart > start ? nextStart : start + chunkSizeChars;
    }
    return pieces;
  }

  private record PageText(int pageNumber, String text) {}

  private record ChunkText(int pageNumber, int chunkIndex, String text) {}
}
