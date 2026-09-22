package it.unisa.performance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
public class OllamaStrategicLineService {

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final RagRetrievalService ragRetrievalService;
  private final String model;
  private final boolean ragEnabled;
  private final int ragTopK;

  public OllamaStrategicLineService(
      RestClient.Builder restClientBuilder,
      ObjectMapper objectMapper,
      RagRetrievalService ragRetrievalService,
      @Value("${ollama.base-url}") String baseUrl,
      @Value("${ollama.model}") String model,
      @Value("${rag.enabled-for-strategic-line-extraction}") boolean ragEnabled,
      @Value("${rag.retrieval-top-k}") int ragTopK) {
    this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    this.objectMapper = objectMapper;
    this.ragRetrievalService = ragRetrievalService;
    this.model = model;
    this.ragEnabled = ragEnabled;
    this.ragTopK = ragTopK;
  }

  // NOTE: ragContext (when enabled) is only ever appended to the prompt below, as clearly labelled
  // supporting material. verifyAgainstSource() further down still checks the model's output against
  // `text` alone — never against ragContext — so the anti-hallucination guarantee for this extractive
  // task is unaffected by whatever the RAG layer returns.
  public List<String> extractCategories(String text) {
    var rawJson = generateRaw(categoriesPrompt(text, ragContextFor(text)), categoriesFormat());
    var found = parseStringArray(rawJson, "categories");
    return verifyAgainstSource(found, text);
  }

  public List<String> extractObjectivesForCategory(String text, String categoryTitle) {
    var rawJson = generateRaw(objectivesPrompt(text, categoryTitle, ragContextFor(text)), objectivesFormat());
    var found = parseStringArray(rawJson, "objectives");
    return verifyAgainstSource(found, text);
  }

  private String ragContextFor(String text) {
    if (!ragEnabled) {
      return "";
    }
    var contentContext = ragRetrievalService.formatForPrompt(
        ragRetrievalService.retrieveContent(text, ragTopK));
    var guidelineContext = ragRetrievalService.formatForPrompt(ragRetrievalService.retrieveGuidelines(ragTopK));
    var sb = new StringBuilder();
    if (!guidelineContext.isBlank()) {
      sb.append("CRITERI METODOLOGICI (da normativa/linee guida caricate, non fanno parte del testo di "
              + "questa pagina):\n").append(compact(guidelineContext, 3000)).append('\n');
    }
    if (!contentContext.isBlank()) {
      sb.append("CONTESTO DI RIFERIMENTO (da altri documenti caricati, solo per continuità terminologica — "
              + "NON e' il testo di questa pagina, NON copiarne frasi come se fossero letterali di questa "
              + "pagina):\n").append(compact(contentContext, 3000));
    }
    return sb.toString();
  }

  private String generateRaw(String prompt, Object format) {
    var response = restClient.post()
        .uri("/api/generate")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new OllamaStrategicLineRequest(model, prompt, format, false, generateOptions()))
        .retrieve()
        .body(OllamaGenerateResponse.class);

    if (response == null || response.response() == null || response.response().isBlank()) {
      throw new IllegalStateException("Ollama non ha restituito testo utile per le linee strategiche");
    }
    return response.response();
  }

  // Page-extraction prompts can carry several pages of raw PDF text, so this needs a bigger window than
  // the server's small default context — set per-call instead of relying on a large server-wide default
  // (which used to force the model into partial CPU offload permanently, even during objective
  // generation which never needed that much context — see OllamaObjectiveService.generateOptions()).
  private Map<String, Object> generateOptions() {
    return Map.of("temperature", 0, "top_p", 0.1, "num_ctx", 16384);
  }

  private List<String> parseStringArray(String rawJson, String fieldName) {
    try {
      var root = readOllamaJson(rawJson);
      var node = root.path(fieldName);
      var values = new ArrayList<String>();
      if (node.isArray()) {
        for (var item : node) {
          var value = item.isTextual() ? item.asText("") : item.path("text").asText("");
          if (!value.isBlank()) {
            values.add(value);
          }
        }
      }
      return values;
    } catch (Exception exception) {
      return List.of();
    }
  }

  private List<String> verifyAgainstSource(List<String> values, String sourceText) {
    var normalizedSource = normalizeForComparison(sourceText);
    var verified = new ArrayList<String>();
    for (var value : values) {
      var normalized = normalizeForComparison(value);
      if (normalized.isBlank() || !normalizedSource.contains(normalized)) {
        continue;
      }
      verified.add(compact(value, 1150));
    }
    return verified;
  }

  // Also folds curly apostrophes and accents: the PDF text uses typographic
  // quotes (dall'art., l'accesso) and apostrophe-for-accent spellings
  // (LEGALITA', ATTIVITA') that the model normalizes away when "copying"
  // verbatim. A stricter comparison here silently drops real, correct
  // extractions as if they were hallucinated.
  private String normalizeForComparison(String value) {
    if (value == null) {
      return "";
    }
    var folded = value.replace('’', '\'').replace('`', '\'');
    folded = java.text.Normalizer.normalize(folded, java.text.Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "");
    return folded.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
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

  private String categoriesPrompt(String text, String ragContext) {
    return """
        Analizza questo estratto (una o più pagine, testo letterale) di un documento di programmazione strategica della Regione Campania.

        Estrai SOLO i titoli delle macro-categorie/linee strategiche presenti: titoli di sezione che identificano un'area di intervento o una priorità di alto livello (es. "AGRICOLTURA", "SANITA'", "5) Internazionalizzazione", "Allargamento della comunità di ricerca").

        NON estrarre: obiettivi o azioni specifiche, premesse, riferimenti normativi, analisi di contesto economico/macroeconomico, il titolo/frontespizio del documento, intestazioni o piè di pagina ripetuti, numeri di pagina, indici.

        Copia ogni titolo ESATTAMENTE come compare nel testo di questa pagina, senza correggere, riassumere o parafrasare. Se in questo testo non ce ne sono, restituisci un elenco vuoto.
        %s

        Restituisci SOLO JSON valido: {"categories": ["...", "..."]}

        Testo:
        %s
        """.formatted(ragContextBlock(ragContext), text);
  }

  private String objectivesPrompt(String text, String categoryTitle, String ragContext) {
    return """
        Analizza questo estratto (una o più pagine, testo letterale) di un documento di programmazione strategica della Regione Campania.

        Estrai SOLO gli obiettivi strategici specifici che appartengono alla linea strategica/macro-categoria: "%s"

        Un obiettivo strategico è OGNI punto elenco (bullet "-", numero, trattino) presente nel testo sotto questo titolo di categoria, un'azione, un intervento o un traguardo concreto dichiarato dall'ente. NON filtrarli in base alla forma grammaticale: un punto elenco è un obiettivo indipendentemente dal fatto che inizi con un verbo all'infinito (es. "predisporre e attuare...", "promuovere lo sviluppo...") o con altre parole (es. "Oltre i fondi...", "Una visione integrata..."). Includi SOLO gli obiettivi chiaramente riconducibili a questa specifica categoria (perché elencati subito dopo il suo titolo o esplicitamente collegati a essa) — non includere obiettivi di altre categorie eventualmente presenti nel testo. Se sotto il titolo ci sono N punti elenco, estraine N, non uno solo.

        NON estrarre: premesse, riferimenti normativi, analisi di contesto economico/macroeconomico, il titolo del documento, intestazioni ripetute.

        Copia ogni obiettivo ESATTAMENTE come compare nel testo di questa pagina, senza correggere, riassumere o parafrasare. Se in questo testo non ci sono obiettivi per questa categoria, restituisci un elenco vuoto — è normale, non forzare l'estrazione.
        %s

        Restituisci SOLO JSON valido: {"objectives": ["...", "..."]}

        Testo:
        %s
        """.formatted(categoryTitle, ragContextBlock(ragContext), text);
  }

  // ragContext here is already the fully-labelled block built by ragContextFor() (methodological rules
  // and/or topical support, each clearly marked as not being this page's literal text) — just wrap it
  // with spacing when present.
  private String ragContextBlock(String ragContext) {
    return ragContext == null || ragContext.isBlank() ? "" : "\n" + ragContext + "\n";
  }

  private Object categoriesFormat() {
    Map<String, Object> rootProperties = new LinkedHashMap<>();
    rootProperties.put("categories", Map.of("type", "array", "items", Map.of("type", "string")));

    Map<String, Object> rootSchema = new LinkedHashMap<>();
    rootSchema.put("type", "object");
    rootSchema.put("properties", rootProperties);
    rootSchema.put("required", List.of("categories"));
    return rootSchema;
  }

  private Object objectivesFormat() {
    Map<String, Object> rootProperties = new LinkedHashMap<>();
    rootProperties.put("objectives", Map.of("type", "array", "items", Map.of("type", "string")));

    Map<String, Object> rootSchema = new LinkedHashMap<>();
    rootSchema.put("type", "object");
    rootSchema.put("properties", rootProperties);
    rootSchema.put("required", List.of("objectives"));
    return rootSchema;
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

  private record OllamaStrategicLineRequest(
      String model,
      String prompt,
      Object format,
      boolean stream,
      Map<String, Object> options) {}

  private record OllamaGenerateResponse(String response) {}
}
