package it.unisa.performance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unisa.performance.domain.StrategicLine;
import it.unisa.performance.dto.StrategicLineImprovementSuggestion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class OllamaStrategicLineImprovementService {

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final String model;

  public OllamaStrategicLineImprovementService(
      RestClient.Builder restClientBuilder,
      ObjectMapper objectMapper,
      @Value("${ollama.base-url}") String baseUrl,
      @Value("${ollama.model}") String model) {
    this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    this.objectMapper = objectMapper;
    this.model = model;
  }

  public List<StrategicLineImprovementSuggestion> analyze(List<StrategicLine> lines) {
    if (lines == null || lines.isEmpty()) {
      return List.of();
    }
    var rawJson = generateRaw(prompt(lines));
    var corrections = parseCorrections(rawJson);
    return buildSuggestions(lines, corrections);
  }

  private String generateRaw(String prompt) {
    var response = restClient.post()
        .uri("/api/generate")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new OllamaImprovementRequest(model, prompt, responseFormat(), false, generateOptions()))
        .retrieve()
        .body(OllamaGenerateResponse.class);

    if (response == null || response.response() == null || response.response().isBlank()) {
      throw new IllegalStateException("Ollama non ha restituito una proposta di miglioramento testo");
    }
    return response.response();
  }

  private Map<String, Object> generateOptions() {
    return Map.of("temperature", 0, "top_p", 0.1);
  }

  private Map<Long, String> parseCorrections(String rawJson) {
    try {
      var root = readOllamaJson(rawJson);
      var node = root.path("corrections");
      Map<Long, String> result = new LinkedHashMap<>();
      if (node.isArray()) {
        for (var item : node) {
          if (!item.path("id").isIntegralNumber()) {
            continue;
          }
          var id = item.path("id").asLong();
          var text = item.path("text").asText("");
          if (!text.isBlank()) {
            result.putIfAbsent(id, text);
          }
        }
      }
      return result;
    } catch (Exception exception) {
      return Map.of();
    }
  }

  private List<StrategicLineImprovementSuggestion> buildSuggestions(
      List<StrategicLine> lines,
      Map<Long, String> corrections) {
    var suggestions = new ArrayList<StrategicLineImprovementSuggestion>();
    for (var line : lines) {
      var corrected = corrections.get(line.getId());
      if (corrected == null) {
        continue;
      }
      var original = line.getDescription();
      if (normalizeForComparison(corrected).equals(normalizeForComparison(original))) {
        continue;
      }
      if (!isPlausibleCorrection(original, corrected)) {
        continue;
      }
      suggestions.add(new StrategicLineImprovementSuggestion(
          line.getId(),
          line.getCode(),
          line.getType().name(),
          original,
          compact(corrected, 1150)));
    }
    return suggestions;
  }

  private boolean isPlausibleCorrection(String original, String corrected) {
    if (original == null || original.isBlank() || corrected == null || corrected.isBlank()) {
      return false;
    }
    var originalLength = original.length();
    var correctedLength = corrected.length();
    var lowerBound = originalLength * 0.5;
    var upperBound = originalLength * 1.5;
    return correctedLength >= lowerBound && correctedLength <= upperBound;
  }

  private String normalizeForComparison(String value) {
    return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  private String prompt(List<StrategicLine> lines) {
    var sb = new StringBuilder();
    for (var line : lines) {
      sb.append("id=").append(line.getId())
          .append(" type=").append(line.getType().name())
          .append(" code=").append(line.getCode())
          .append('\n')
          .append("  testo: ").append(compact(line.getDescription(), 1150))
          .append("\n\n");
    }

    return """
        Rivedi questo elenco di linee strategiche e obiettivi strategici, estratti automaticamente da testo PDF. Possono contenere artefatti di estrazione (legature rotte come "e;ettivo" invece di "effettivo", "con:scati" invece di "confiscati", ";" o ":" al posto di lettere mancanti), refusi, punteggiatura o spaziatura errata.

        Per OGNI riga (usa l'id esatto indicato) correggi ESCLUSIVAMENTE:
        - artefatti di estrazione PDF (legature rotte, caratteri sostituiti o mancanti);
        - refusi ed errori di battitura;
        - punteggiatura o spaziatura errata;
        - errori grammaticali evidenti.

        NON modificare il significato, NON riassumere, NON parafrasare, NON aggiungere o rimuovere informazioni, NON tradurre. Se una riga non ha problemi da correggere, restituisci il testo IDENTICO a quello fornito.

        Includi un id per OGNI riga dell'elenco, nessuna esclusa.

        Restituisci SOLO JSON valido:
        {"corrections": [{"id": 12, "text": "testo corretto o identico all'originale"}]}

        Elenco:
        %s
        """.formatted(sb.toString());
  }

  private Object responseFormat() {
    Map<String, Object> itemProperties = new LinkedHashMap<>();
    itemProperties.put("id", Map.of("type", "integer"));
    itemProperties.put("text", Map.of("type", "string"));

    Map<String, Object> itemSchema = new LinkedHashMap<>();
    itemSchema.put("type", "object");
    itemSchema.put("properties", itemProperties);
    itemSchema.put("required", List.of("id", "text"));

    Map<String, Object> rootProperties = new LinkedHashMap<>();
    rootProperties.put("corrections", Map.of("type", "array", "items", itemSchema));

    Map<String, Object> rootSchema = new LinkedHashMap<>();
    rootSchema.put("type", "object");
    rootSchema.put("properties", rootProperties);
    rootSchema.put("required", List.of("corrections"));
    return rootSchema;
  }

  private JsonNode readOllamaJson(String rawJson) throws Exception {
    try {
      return objectMapper.readTree(rawJson);
    } catch (Exception ignored) {
      var trimmed = rawJson == null ? "" : rawJson.trim();
      int start = trimmed.indexOf('{');
      int end = trimmed.lastIndexOf('}');
      if (start >= 0 && end > start) {
        return objectMapper.readTree(trimmed.substring(start, end + 1));
      }
      throw ignored;
    }
  }

  private String compact(String value, int maxLength) {
    if (value == null) {
      return "";
    }
    var normalized = value.trim().replaceAll("\\s+", " ");
    if (normalized.length() <= maxLength) {
      return normalized;
    }
    return normalized.substring(0, maxLength - 1).trim() + "…";
  }

  private record OllamaImprovementRequest(
      String model,
      String prompt,
      Object format,
      boolean stream,
      Map<String, Object> options) {}

  private record OllamaGenerateResponse(String response) {}
}
