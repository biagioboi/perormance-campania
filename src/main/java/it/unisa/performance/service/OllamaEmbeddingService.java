package it.unisa.performance.service;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class OllamaEmbeddingService {

  private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
  private static final int READ_TIMEOUT_MILLIS = 60_000;

  private final RestClient restClient;
  private final String model;

  public OllamaEmbeddingService(
      RestClient.Builder restClientBuilder,
      @Value("${ollama.base-url}") String baseUrl,
      @Value("${ollama.embedding-model}") String model) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
    requestFactory.setReadTimeout(READ_TIMEOUT_MILLIS);
    this.restClient = restClientBuilder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    this.model = model;
  }

  /** Returns one embedding vector per input text, same order. Small, fast embedding model — no batching limits expected at this dataset size (hundreds of texts). */
  public List<float[]> embed(List<String> texts) {
    if (texts.isEmpty()) {
      return List.of();
    }
    var response = restClient.post()
        .uri("/api/embed")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new EmbedRequest(model, texts))
        .retrieve()
        .body(EmbedResponse.class);

    if (response == null || response.embeddings() == null || response.embeddings().size() != texts.size()) {
      throw new IllegalStateException("Ollama non ha restituito embedding validi");
    }
    return response.embeddings();
  }

  public static double cosineSimilarity(float[] a, float[] b) {
    double dot = 0;
    double normA = 0;
    double normB = 0;
    for (var i = 0; i < a.length; i++) {
      dot += a[i] * b[i];
      normA += a[i] * a[i];
      normB += b[i] * b[i];
    }
    if (normA == 0 || normB == 0) {
      return 0;
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }

  private record EmbedRequest(String model, List<String> input) {}

  private record EmbedResponse(String model, List<float[]> embeddings) {}
}
