package it.unisa.performance.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Retrieves supporting context from the RAG knowledge base (sources uploaded via {@link RagIngestionService})
 * for injection into an Ollama prompt. Fail-soft: any embedding/Qdrant error yields an empty result
 * rather than propagating, so generation never blocks on the RAG layer being unavailable.
 */
@Service
public class RagRetrievalService {

  // Fixed query for GUIDELINE-purpose sources (D.Lgs. 150/2009, linee guida/manuale PIAO, ecc.): these
  // are procedural/normative documents whose rules apply to every generation regardless of the specific
  // strategic line's topic, so they must NOT be retrieved by topical similarity to the line (a chunk
  // about "criteri di valore pubblico" will never score high against "AGRICOLTURA"). Instead they're
  // always queried with this fixed description of what we're looking for.
  private static final String GUIDELINE_QUERY =
      "Criteri e regole per la formulazione di obiettivi, indicatori, target, pesi e valore pubblico "
          + "nel ciclo della performance secondo il D.Lgs. 150/2009 e le linee guida/manuale PIAO.";

  private final OllamaEmbeddingService embeddingService;
  private final QdrantClientService qdrantClientService;

  // GUIDELINE_QUERY never changes, so its embedding is computed at most once and reused for the life of
  // the app instead of being re-requested from Ollama on every single line of a generation run. This
  // matters a lot in practice: a generation run used to issue one embedding call per line for structure
  // matching; adding an uncached per-line embedding call here for a CONSTANT query was enough to keep
  // the embedding model and the (much larger) generation model both resident in VRAM continuously,
  // which triggers severe GPU contention on this host (see OllamaObjectiveService's READ_TIMEOUT_MILLIS
  // comment) — observed in practice as every /api/generate call timing out at exactly 120s from the 9th
  // line onward in a 55-line run, versus ~10-15s for the first 8 lines.
  private volatile float[] guidelineVectorCache;

  public RagRetrievalService(
      OllamaEmbeddingService embeddingService, QdrantClientService qdrantClientService) {
    this.embeddingService = embeddingService;
    this.qdrantClientService = qdrantClientService;
  }

  public record RagContextChunk(String sourceName, Integer pageNumber, String text, double score) {}

  /** Content sources: retrieved by topical similarity to {@code queryText} (typically the strategic line). */
  public List<RagContextChunk> retrieveContent(String queryText, int topK) {
    if (queryText == null || queryText.isBlank() || topK <= 0) {
      return List.of();
    }
    try {
      var vector = embeddingService.embed(List.of(queryText)).get(0);
      return searchAndMap(vector, topK, purposeFilter("CONTENT"));
    } catch (RuntimeException exception) {
      return List.of();
    }
  }

  /**
   * Same as {@link #retrieveContent(String, int)} but for a caller that already had this exact query
   * embedded for another purpose (e.g. structure similarity matching) — avoids a second, redundant
   * embedding call for text that hasn't changed. Pass {@code null} vector to get an empty result
   * (equivalent to the caller's own embedding having failed) instead of re-deriving one here.
   */
  public List<RagContextChunk> retrieveContentByVector(float[] vector, int topK) {
    if (vector == null || topK <= 0) {
      return List.of();
    }
    try {
      return searchAndMap(vector, topK, purposeFilter("CONTENT"));
    } catch (RuntimeException exception) {
      return List.of();
    }
  }

  /** Guideline sources: always retrieved with the same fixed methodological query, never by line topic. */
  public List<RagContextChunk> retrieveGuidelines(int topK) {
    if (topK <= 0) {
      return List.of();
    }
    try {
      var vector = guidelineVectorCache;
      if (vector == null) {
        vector = embeddingService.embed(List.of(GUIDELINE_QUERY)).get(0);
        guidelineVectorCache = vector;
      }
      return searchAndMap(vector, topK, purposeFilter("GUIDELINE"));
    } catch (RuntimeException exception) {
      return List.of();
    }
  }

  private Map<String, Object> purposeFilter(String purpose) {
    return Map.of("must", List.of(Map.of("key", "purpose", "match", Map.of("value", purpose))));
  }

  private List<RagContextChunk> searchAndMap(float[] vector, int topK, Map<String, Object> filter) {
    return qdrantClientService.search(vector, topK, filter).stream()
        .map(this::toChunk)
        .toList();
  }

  /** Returns "" (never null) when there is nothing to inject, so callers can always append it as-is. */
  public String formatForPrompt(List<RagContextChunk> chunks) {
    if (chunks == null || chunks.isEmpty()) {
      return "";
    }
    return chunks.stream()
        .map(chunk -> "[Fonte: " + chunk.sourceName()
            + (chunk.pageNumber() != null ? ", pag. " + chunk.pageNumber() : "")
            + "]\n" + chunk.text())
        .collect(Collectors.joining("\n---\n"));
  }

  private RagContextChunk toChunk(QdrantClientService.SearchResult result) {
    var payload = result.payload();
    var sourceName = payload.get("source_name") instanceof String value ? value : "fonte sconosciuta";
    var pageNumber = payload.get("page_number") instanceof Number value ? value.intValue() : null;
    var text = payload.get("text") instanceof String value ? value : "";
    return new RagContextChunk(sourceName, pageNumber, text, result.score());
  }
}
