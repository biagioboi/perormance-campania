package it.unisa.performance.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Thin REST wrapper around Qdrant, the dedicated vector store used to persist RAG source chunks.
 * Every method is fail-soft on the read path (search returns an empty list rather than throwing)
 * so a Qdrant outage degrades generation quality instead of blocking it, matching the existing
 * fallback behaviour of {@link OllamaEmbeddingService}-based retrieval in {@code OllamaObjectiveService}.
 */
@Service
public class QdrantClientService {

  private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
  private static final int READ_TIMEOUT_MILLIS = 30_000;
  private static final int VECTOR_SIZE = 768;

  private final RestClient restClient;
  private final String collection;
  private volatile boolean collectionEnsured = false;

  public QdrantClientService(
      RestClient.Builder restClientBuilder,
      @Value("${qdrant.base-url}") String baseUrl,
      @Value("${qdrant.collection}") String collection) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
    requestFactory.setReadTimeout(READ_TIMEOUT_MILLIS);
    this.restClient = restClientBuilder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    this.collection = collection;
  }

  /** Idempotent: creates the collection on first use, no-ops afterwards (and if it already exists). */
  public void ensureCollection() {
    if (collectionEnsured) {
      return;
    }
    try {
      restClient.put()
          .uri("/collections/" + collection)
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("vectors", Map.of("size", VECTOR_SIZE, "distance", "Cosine")))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException ignored) {
      // Already exists, or Qdrant briefly unreachable — either way the next call will retry.
    }
    collectionEnsured = true;
  }

  public void upsert(List<Point> points) {
    if (points.isEmpty()) {
      return;
    }
    ensureCollection();
    try {
      restClient.put()
          .uri("/collections/" + collection + "/points")
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("points", points))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException exception) {
      throw new IllegalStateException("Impossibile salvare i chunk RAG su Qdrant", exception);
    }
  }

  public List<SearchResult> search(float[] vector, int topK) {
    return search(vector, topK, null);
  }

  /** {@code filter}, when given, is a Qdrant filter object (e.g. {"must":[{"key":"purpose","match":{"value":"CONTENT"}}]}). */
  public List<SearchResult> search(float[] vector, int topK, Map<String, Object> filter) {
    try {
      ensureCollection();
      var response = restClient.post()
          .uri("/collections/" + collection + "/points/search")
          .contentType(MediaType.APPLICATION_JSON)
          .body(new SearchRequest(toList(vector), topK, true, filter))
          .retrieve()
          .body(SearchResponse.class);
      return response == null || response.result() == null ? List.of() : response.result();
    } catch (RestClientException | IllegalStateException exception) {
      return List.of();
    }
  }

  public void deleteBySource(long sourceId) {
    try {
      restClient.post()
          .uri("/collections/" + collection + "/points/delete")
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("filter", Map.of("must", List.of(
              Map.of("key", "source_id", "match", Map.of("value", sourceId))))))
          .retrieve()
          .toBodilessEntity();
    } catch (RestClientException exception) {
      throw new IllegalStateException("Impossibile eliminare i chunk della fonte da Qdrant", exception);
    }
  }

  private List<Float> toList(float[] vector) {
    var list = new ArrayList<Float>(vector.length);
    for (float value : vector) {
      list.add(value);
    }
    return list;
  }

  public record Point(String id, List<Float> vector, Map<String, Object> payload) {
    public static Point of(float[] vector, Map<String, Object> payload) {
      var list = new ArrayList<Float>(vector.length);
      for (float value : vector) {
        list.add(value);
      }
      return new Point(UUID.randomUUID().toString(), list, payload);
    }
  }

  public record SearchResult(String id, double score, Map<String, Object> payload) {}

  private record SearchRequest(
      List<Float> vector,
      int limit,
      @JsonProperty("with_payload") boolean withPayload,
      Map<String, Object> filter) {}

  private record SearchResponse(List<SearchResult> result) {}
}
