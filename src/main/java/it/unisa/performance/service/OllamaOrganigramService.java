package it.unisa.performance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unisa.performance.dto.ExtractedStructureResponse;
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
public class OllamaOrganigramService {

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final RagRetrievalService ragRetrievalService;
  private final String model;
  private final int ragTopK;

  public OllamaOrganigramService(
      RestClient.Builder restClientBuilder,
      ObjectMapper objectMapper,
      RagRetrievalService ragRetrievalService,
      @Value("${ollama.base-url}") String baseUrl,
      @Value("${ollama.vision-model}") String model,
      @Value("${rag.retrieval-top-k}") int ragTopK) {
    this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    this.objectMapper = objectMapper;
    this.ragRetrievalService = ragRetrievalService;
    this.model = model;
    this.ragTopK = ragTopK;
  }

  public List<ExtractedStructureResponse> extractStructures(String extractedText, List<String> pageImagesBase64) {
    var contentContext = ragRetrievalService.formatForPrompt(
        ragRetrievalService.retrieveContent(extractedText, ragTopK));
    var guidelineContext = ragRetrievalService.formatForPrompt(ragRetrievalService.retrieveGuidelines(ragTopK));
    var rawJson = generateRaw(
        prompt(extractedText, !pageImagesBase64.isEmpty(), contentContext, guidelineContext), pageImagesBase64);
    return parseStructures(rawJson);
  }

  public String modelName() {
    return model;
  }

  private String generateRaw(String prompt, List<String> pageImagesBase64) {
    var response = restClient.post()
        .uri("/api/generate")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new OllamaOrganigramRequest(model, prompt, pageImagesBase64, responseFormat(), false, generateOptions()))
        .retrieve()
        .body(OllamaGenerateResponse.class);

    if (response == null || response.response() == null || response.response().isBlank()) {
      throw new IllegalStateException("Ollama non ha restituito testo utile per l'organigramma");
    }
    return response.response();
  }

  // Organigram prompts carry up to ~32000 chars of page text plus page images, so this needs the
  // largest window of the three Ollama services — set per-call instead of a large server-wide default
  // (see OllamaObjectiveService.generateOptions() for why a big server default is the wrong lever).
  private Map<String, Object> generateOptions() {
    return Map.of("temperature", 0, "top_p", 0.1, "num_ctx", 32768);
  }

  private List<ExtractedStructureResponse> parseStructures(String rawJson) {
    try {
      var root = readOllamaJson(rawJson);
      return normalizeHierarchy(collectStructures(structuresNode(root)));
    } catch (Exception exception) {
      var recovered = recoverStructures(rawJson);
      if (!recovered.isEmpty()) {
        return normalizeHierarchy(recovered);
      }
      throw new IllegalStateException("Impossibile interpretare la risposta Ollama per l'organigramma: " + rawJson, exception);
    }
  }

  private List<ExtractedStructureResponse> collectStructures(JsonNode structuresNode) {
    if (!structuresNode.isArray()) {
      throw new IllegalStateException("Risposta Ollama priva dell'array structures");
    }

    Map<String, ExtractedStructureResponse> ordered = new LinkedHashMap<>();
    int fallbackIndex = 1;
    for (var node : structuresNode) {
      var structure = toStructure(node, fallbackIndex++);
      if (structure == null || structure.name().isBlank()) {
        continue;
      }
      ordered.putIfAbsent(structureKey(structure), structure);
    }

    if (ordered.isEmpty()) {
      throw new IllegalStateException("Risposta Ollama senza strutture organizzative valide");
    }
    return new ArrayList<>(ordered.values());
  }

  private List<ExtractedStructureResponse> recoverStructures(String rawJson) {
    Map<String, ExtractedStructureResponse> ordered = new LinkedHashMap<>();
    int fallbackIndex = 1;
    for (var objectSnippet : structureObjectSnippets(rawJson)) {
      try {
        var node = objectMapper.readTree(objectSnippet);
        var structure = toStructure(node, fallbackIndex++);
        if (structure == null || structure.name().isBlank()) {
          continue;
        }
        ordered.putIfAbsent(structureKey(structure), structure);
      } catch (Exception ignored) {
        // Best-effort recovery: skip malformed trailing fragments.
      }
    }
    return new ArrayList<>(ordered.values());
  }

  private List<ExtractedStructureResponse> normalizeHierarchy(List<ExtractedStructureResponse> structures) {
    if (structures.isEmpty()) {
      return structures;
    }

    Map<String, ExtractedStructureResponse> byExternalKey = new LinkedHashMap<>();
    Map<String, Integer> childCountByParent = new LinkedHashMap<>();
    for (var structure : structures) {
      byExternalKey.put(structure.externalKey(), structure);
      if (structure.parentExternalKey() != null && !structure.parentExternalKey().isBlank()) {
        childCountByParent.merge(structure.parentExternalKey(), 1, Integer::sum);
      }
    }

    var normalized = new ArrayList<ExtractedStructureResponse>(structures.size());
    for (var structure : structures) {
      var parentExternalKey = structure.parentExternalKey();
      if (parentExternalKey != null && !byExternalKey.containsKey(parentExternalKey)) {
        parentExternalKey = null;
      }
      normalized.add(new ExtractedStructureResponse(
          structure.externalKey(),
          parentExternalKey,
          structure.code(),
          structure.name(),
          inferHierarchyType(structure.type(), parentExternalKey, childCountByParent.containsKey(structure.externalKey())),
          structure.area()));
    }
    return normalized;
  }

  private String inferHierarchyType(String rawType, String parentExternalKey, boolean hasChildren) {
    var type = sanitizeType(rawType);
    if ("direzione_generale".equals(type)) {
      return type;
    }
    if ("settore".equals(type)) {
      return type;
    }
    if (hasChildren) {
      return parentExternalKey == null ? "direzione_generale" : "settore";
    }
    if (parentExternalKey != null) {
      return "uos";
    }
    return "direzione_generale";
  }

  private List<String> structureObjectSnippets(String rawJson) {
    var snippets = new ArrayList<String>();
    if (rawJson == null || rawJson.isBlank()) {
      return snippets;
    }

    int structuresIndex = rawJson.indexOf("\"structures\"");
    int arrayStart = structuresIndex >= 0 ? rawJson.indexOf('[', structuresIndex) : rawJson.indexOf('[');
    if (arrayStart < 0) {
      return snippets;
    }

    boolean inString = false;
    boolean escaping = false;
    int depth = 0;
    int objectStart = -1;

    for (int i = arrayStart; i < rawJson.length(); i++) {
      char ch = rawJson.charAt(i);
      if (escaping) {
        escaping = false;
        continue;
      }
      if (ch == '\\') {
        escaping = true;
        continue;
      }
      if (ch == '"') {
        inString = !inString;
        continue;
      }
      if (inString) {
        continue;
      }
      if (ch == '{') {
        if (depth == 0) {
          objectStart = i;
        }
        depth++;
      } else if (ch == '}') {
        if (depth > 0) {
          depth--;
          if (depth == 0 && objectStart >= 0) {
            snippets.add(rawJson.substring(objectStart, i + 1));
            objectStart = -1;
          }
        }
      } else if (ch == ']' && depth == 0) {
        break;
      }
    }
    return snippets;
  }

  private JsonNode structuresNode(JsonNode root) {
    if (root == null || root.isNull()) {
      return objectMapper.createArrayNode();
    }
    if (root.isArray()) {
      return root;
    }
    var structures = root.path("structures");
    if (structures.isArray()) {
      return structures;
    }
    return objectMapper.createArrayNode();
  }

  private ExtractedStructureResponse toStructure(JsonNode node, int fallbackIndex) {
    var name = compact(requiredishText(node, "name", "title", "label"), 280);
    if (name.isBlank()) {
      return null;
    }

    var externalKey = compact(optionalText(node, "externalKey", "key", "id"), 64);
    if (externalKey.isBlank()) {
      externalKey = "ORG-" + fallbackIndex;
    }

    var parentExternalKey = compact(optionalText(node, "parentExternalKey", "parentKey", "parentId"), 64);
    if (parentExternalKey.isBlank() || "null".equalsIgnoreCase(parentExternalKey)) {
      parentExternalKey = null;
    }

    var code = compact(optionalText(node, "code", "structureCode"), 64);
    var type = sanitizeType(optionalText(node, "type", "kind", "level"));
    var area = compact(optionalText(node, "area"), 80);

    return new ExtractedStructureResponse(
        externalKey,
        parentExternalKey,
        code,
        name,
        type,
        area);
  }

  private String structureKey(ExtractedStructureResponse structure) {
    return normalize(structure.code()) + "|" + normalize(structure.name());
  }

  private String prompt(String extractedText, boolean hasImages, String contentContext, String guidelineContext) {
    return """
        Analizza UNA SINGOLA PAGINA OCR/estratta dell'organigramma della Regione Campania e restituisci SOLO JSON valido.

        Come e' fatto il diagramma di questa pagina (vale per la stragrande maggioranza delle pagine):
        - C'e' UN SOLO nodo di primo livello (Direzione Generale, Ufficio Speciale, Struttura di Missione o simile), in cima al diagramma, di solito il box piu' in alto e in un colore diverso (spesso azzurro/blu) da quello degli altri nodi.
        - Da quel nodo di vertice partono, collegati da linee, uno o piu' Settori (spesso verdi).
        - Da ciascun Settore partono, collegate da linee, una o piu' UOS (spesso blu piu' scuro/celeste).
        - Alcune UOS possono essere collegate DIRETTAMENTE al nodo di vertice, senza passare da un Settore: restano comunque UOS, non diventano il nodo di vertice.
        - REGOLA CRITICA: se un nodo e' collegato (anche indirettamente, tramite un Settore) sotto il nodo di vertice della pagina, NON puo' essere a sua volta "direzione_generale" — al massimo e' "settore" o "uos". Il type "direzione_generale" va usato SOLO per il nodo di vertice della pagina (ce n'e' uno solo per pagina, salvo rari casi).

        Obiettivo principale:
        - esplicitare la gerarchia Direzione Generale -> Settore -> UOS seguendo le linee di collegamento del diagramma, non solo l'ordine del testo
        - gestire anche il caso in cui una UOS dipenda direttamente dalla Direzione Generale senza Settore intermedio
        - eliminare duplicati dovuti a OCR o layout a colonne
        - ignorare descrizioni narrative, frammenti di testo spezzati e contenuti non strutturali

        Restituisci un oggetto JSON con questa forma:
        {
          "structures": [
            {
              "externalKey": "chiave-stabile-univoca",
              "parentExternalKey": "chiave-del-padre oppure null",
              "code": "codice ufficiale se presente altrimenti stringa vuota",
              "name": "nome della struttura",
              "type": "direzione_generale|settore|uos|ufficio_diretta_collaborazione|staff|uod|ufficio|struttura",
              "area": "area tematica sintetica o stringa vuota"
            }
          ]
        }

        Regole importanti:
        - Ragiona solo sulla pagina fornita, ma se in questa pagina compaiono Settori o UOS devi ripetere esplicitamente anche la Direzione Generale padre e gli eventuali Settori padre necessari a collegarli.
        - I Settori sono macro-categorie di UOS.
        - Se una UOS dipende direttamente da una Direzione Generale, usa come parentExternalKey la Direzione Generale.
        - Usa parentExternalKey = null SOLO per il nodo di vertice della pagina (il caso normale: un solo nodo radice per pagina).
        - Se il codice non e leggibile, lascia code vuoto.
        - Se il livello non e chiaro ma il nodo sembra intermedio tra DG e UOS, preferisci type = "settore".
        - Se il livello non e chiaro e non puoi dedurlo, usa type = "struttura", MAI "direzione_generale" a meno che non sia davvero il nodo di vertice della pagina.
        - Non inventare strutture che non compaiono nella pagina.
        - Mantieni i nomi puliti, senza descrizioni lunghe delle attivita.
        - Se le immagini sono presenti, usale come fonte primaria per la gerarchia (posizione dei box e linee di collegamento), non solo per correggere il testo OCR.

        %s
        %s

        Contenuto pagina:
        %s
        """.formatted(
        hasImages
            ? "Sono state fornite anche immagini della singola pagina PDF."
            : "Non sono state fornite immagini; usa il testo estratto come fonte principale.",
        ragContextBlock(contentContext, guidelineContext),
        compact(extractedText, 32000));
  }

  private String ragContextBlock(String contentContext, String guidelineContext) {
    var sb = new StringBuilder();
    if (guidelineContext != null && !guidelineContext.isBlank()) {
      sb.append("CRITERI METODOLOGICI (regole da normativa/linee guida caricate — rispettale, non sono "
              + "un contenuto della pagina):\n")
          .append(compact(guidelineContext, 3000))
          .append("\n\n");
    }
    if (contentContext != null && !contentContext.isBlank()) {
      sb.append("CONTESTO DI RIFERIMENTO (materiale di supporto da altri documenti organizzativi caricati — "
              + "solo per riconoscere nomenclature ricorrenti, NON e' la fonte primaria per questa pagina):\n")
          .append(compact(contentContext, 4000));
    }
    return sb.toString();
  }


  private Object responseFormat() {
    Map<String, Object> itemProperties = new LinkedHashMap<>();
    itemProperties.put("externalKey", Map.of("type", "string"));
    itemProperties.put("parentExternalKey", Map.of("type", "string"));
    itemProperties.put("code", Map.of("type", "string"));
    itemProperties.put("name", Map.of("type", "string"));
    itemProperties.put("type", Map.of(
        "type", "string",
        "enum", List.of(
            "ufficio_diretta_collaborazione",
            "direzione_generale",
            "staff",
            "settore",
            "uod",
            "uos",
            "ufficio",
            "struttura")));
    itemProperties.put("area", Map.of("type", "string"));

    Map<String, Object> itemSchema = new LinkedHashMap<>();
    itemSchema.put("type", "object");
    itemSchema.put("properties", itemProperties);
    itemSchema.put("required", List.of("externalKey", "parentExternalKey", "code", "name", "type", "area"));

    Map<String, Object> rootProperties = new LinkedHashMap<>();
    rootProperties.put("structures", Map.of("type", "array", "items", itemSchema));

    Map<String, Object> rootSchema = new LinkedHashMap<>();
    rootSchema.put("type", "object");
    rootSchema.put("properties", rootProperties);
    rootSchema.put("required", List.of("structures"));
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
      start = trimmed.indexOf('[');
      end = trimmed.lastIndexOf(']');
      if (start >= 0 && end > start) {
        return objectMapper.readTree(trimmed.substring(start, end + 1));
      }
      throw ignored;
    }
  }

  private String requiredishText(JsonNode node, String... names) {
    for (var name : names) {
      var value = node.path(name).asText("").trim();
      if (!value.isBlank()) {
        return value;
      }
    }
    return "";
  }

  private String optionalText(JsonNode node, String... names) {
    return requiredishText(node, names);
  }

  private String sanitizeType(String type) {
    var normalized = normalize(type);
    if (normalized.contains("diretta") || normalized.contains("gabinetto")) {
      return "ufficio_diretta_collaborazione";
    }
    if (normalized.contains("direzione generale")) {
      return "direzione_generale";
    }
    if (normalized.equals("staff") || normalized.contains("staff")) {
      return "staff";
    }
    if (normalized.contains("settore")) {
      return "settore";
    }
    if (normalized.contains("uod")) {
      return "uod";
    }
    if (normalized.contains("uos")) {
      return "uos";
    }
    if (normalized.contains("ufficio")) {
      return "ufficio";
    }
    return "struttura";
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
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

  private record OllamaOrganigramRequest(
      String model,
      String prompt,
      List<String> images,
      Object format,
      boolean stream,
      Map<String, Object> options) {}

  private record OllamaGenerateResponse(String response) {}
}
