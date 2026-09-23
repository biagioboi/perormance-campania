package it.unisa.performance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unisa.performance.domain.Direction;
import it.unisa.performance.domain.StrategicLine;
import it.unisa.performance.domain.StructureUnit;
import it.unisa.performance.dto.GenerateObjectivesRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class OllamaObjectiveService {

  private static final Logger log = LoggerFactory.getLogger(OllamaObjectiveService.class);
  private static final int DEFAULT_ACTION_WEIGHT = 20;
  private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
  // A hung Ollama call (GPU contention from other processes on the host) used to block a generation
  // job forever with no way to recover short of restarting the app. A bounded read timeout turns that
  // into a normal exception, which the per-line retry/skip logic already handles. Raised from 120s to
  // 300s: under GPU contention calls are slow, not stuck (GPU stays busy, just time-sliced with other
  // processes), so a longer budget lets more lines actually finish instead of being aborted right as
  // they were about to complete.
  private static final int READ_TIMEOUT_MILLIS = 300_000;
  // Retrieval width: how many of the most semantically relevant structures to show the model per
  // line, instead of dumping the entire catalog (which used to make every single-line call slower
  // and more prone to timing out under GPU contention, especially for "Trasversale" lines that
  // previously saw every structure with no filtering at all).
  private static final int STRUCTURE_TOP_K = 20;

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final OllamaEmbeddingService embeddingService;
  private final RagRetrievalService ragRetrievalService;
  private final String model;
  private final int ragTopK;

  public OllamaObjectiveService(
      RestClient.Builder restClientBuilder,
      ObjectMapper objectMapper,
      OllamaEmbeddingService embeddingService,
      RagRetrievalService ragRetrievalService,
      @Value("${ollama.base-url}") String baseUrl,
      @Value("${ollama.model}") String model,
      @Value("${rag.retrieval-top-k}") int ragTopK) {
    var requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
    requestFactory.setReadTimeout(READ_TIMEOUT_MILLIS);
    this.restClient = restClientBuilder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    this.objectMapper = objectMapper;
    this.embeddingService = embeddingService;
    this.ragRetrievalService = ragRetrievalService;
    this.model = model;
    this.ragTopK = ragTopK;
  }

  /**
   * Generates objectives line by line, delivering each line's results to {@code onLineCompleted} as
   * soon as they're ready (instead of aggregating everything in memory) so the caller can persist
   * incrementally — a slow/contended run no longer has to finish entirely before anything is saved.
   */
  public void generateAssigned(
      List<StrategicLine> lines,
      List<StructureUnit> structures,
      GenerateObjectivesRequest request,
      Consumer<String> progress,
      BiConsumer<StrategicLine, List<AssignedObjectiveTemplate>> onLineCompleted) {
    var structureEmbeddings = embedStructures(structures, progress);
    for (var i = 0; i < lines.size(); i++) {
      var line = lines.get(i);
      progress.accept("Ollama: linea " + (i + 1) + "/" + lines.size() + " (" + line.getCode() + ")");
      try {
        var lineVector = lineVectorFor(line, structureEmbeddings);
        var candidateStructures = topKStructures(line, structures, structureEmbeddings, lineVector);
        var assignedForLine = generateAssignedForLine(line, candidateStructures, request, lineVector);
        onLineCompleted.accept(line, assignedForLine);
        progress.accept(
            "Ollama: linea " + line.getCode() + " completata (" + assignedForLine.size() + " obiettivi validi)");
      } catch (RuntimeException exception) {
        progress.accept(
            "Ollama: salto linea " + line.getCode() + " per errore non bloccante: "
                + compact(exception.getMessage(), 220));
      }
    }
  }

  private Map<String, float[]> embedStructures(List<StructureUnit> structures, Consumer<String> progress) {
    try {
      var texts = structures.stream().map(this::structureEmbeddingText).toList();
      var vectors = embeddingService.embed(texts);
      var byCode = new LinkedHashMap<String, float[]>();
      for (var i = 0; i < structures.size(); i++) {
        byCode.put(structures.get(i).getCode(), vectors.get(i));
      }
      return byCode;
    } catch (RuntimeException exception) {
      progress.accept(
          "Ollama: embedding strutture non disponibile, uso il filtro per area come fallback: "
              + compact(exception.getMessage(), 160));
      return Map.of();
    }
  }

  // Computed once per line and reused both for structure-similarity ranking and for RAG content
  // retrieval below, instead of embedding the same lineEmbeddingText(line) twice — see the note on
  // RagRetrievalService.guidelineVectorCache for why doubling up on embedding calls matters here.
  private float[] lineVectorFor(StrategicLine line, Map<String, float[]> structureEmbeddings) {
    if (structureEmbeddings.isEmpty()) {
      return null;
    }
    try {
      return embeddingService.embed(List.of(lineEmbeddingText(line))).get(0);
    } catch (RuntimeException exception) {
      return null;
    }
  }

  private List<StructureUnit> topKStructures(
      StrategicLine line, List<StructureUnit> structures, Map<String, float[]> structureEmbeddings,
      float[] lineVector) {
    if (structureEmbeddings.isEmpty() || lineVector == null) {
      // Embedding service unavailable: fall back to the coarser area filter rather than sending
      // every structure to every line.
      var byArea = structures.stream()
          .filter(structure -> structure.getArea().equals(line.getArea()) || "Trasversale".equals(line.getArea()))
          .toList();
      return byArea.isEmpty() ? structures : byArea;
    }
    return structures.stream()
        .sorted(Comparator.comparingDouble(
                (StructureUnit structure) -> OllamaEmbeddingService.cosineSimilarity(
                    lineVector, structureEmbeddings.getOrDefault(structure.getCode(), lineVector)))
            .reversed())
        .limit(STRUCTURE_TOP_K)
        .toList();
  }

  private String structureEmbeddingText(StructureUnit structure) {
    var dg = direzioneGeneraleOf(structure);
    return structure.getName() + " | " + structure.getType() + " | " + structure.getArea() + " | " + dg.getName();
  }

  private String lineEmbeddingText(StrategicLine line) {
    return line.getTitle() + " | " + line.getArea() + " | " + line.getDescription();
  }

  private List<AssignedObjectiveTemplate> generateAssignedForLine(
      StrategicLine line,
      List<StructureUnit> candidateStructures,
      GenerateObjectivesRequest request,
      float[] lineVector) {
    var contentContext = ragRetrievalService.formatForPrompt(
        ragRetrievalService.retrieveContentByVector(lineVector, ragTopK));
    var guidelineContext = ragRetrievalService.formatForPrompt(
        ragRetrievalService.retrieveGuidelines(ragTopK));
    log.info(
        "RAG per linea {}: contenuto={} caratteri, criteri metodologici={} caratteri",
        line.getCode(), contentContext.length(), guidelineContext.length());
    var prompt = assignmentPrompt(line, candidateStructures, request, contentContext, guidelineContext);
    RuntimeException lastException = null;
    String rawResponse = "";

    for (var attempt = 1; attempt <= 2; attempt++) {
      rawResponse = generateRaw(prompt);
      try {
        var assigned = parseAssignedObjectives(rawResponse);
        validateAssignedObjectivesForLine(assigned, line, candidateStructures);
        return assigned.stream()
            .filter(objective -> resolveKnownCode(objective.lineCode(), Set.of(line.getCode())).isPresent())
            .limit(request.nPerLine())
            .toList();
      } catch (RuntimeException exception) {
        lastException = exception;
        prompt = retryAssignmentPrompt(line, candidateStructures, request, exception.getMessage());
      }
    }

    throw new IllegalStateException(
        "Ollama non ha restituito obiettivi validi per " + line.getCode()
            + " dopo il retry: " + lastException.getMessage(),
        lastException);
  }

  private String generateRaw(String prompt) {
    var response = restClient.post()
        .uri("/api/generate")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new OllamaGenerateRequest(model, prompt, "json", false, generateOptions()))
        .retrieve()
        .body(OllamaGenerateResponse.class);

    if (response == null || response.response() == null || response.response().isBlank()) {
      throw new IllegalStateException("Ollama non ha restituito testo utile");
    }
    return response.response();
  }

  // Objective-generation prompts are small (line info + top-K structures + compact RAG context), so a
  // modest num_ctx keeps this call's KV-cache footprint low even when the Ollama server's own default
  // context length is small — unlike import/extraction calls (see OllamaStrategicLineService,
  // OllamaOrganigramService), which need a much bigger window for full-page text and are set per-call
  // there rather than by relying on a large server-wide default context (the server-wide default used
  // to be 131072, which forced partial CPU offload of the model at all times, even for these small
  // objective-generation calls that never needed it).
  private Map<String, Object> generateOptions() {
    return Map.of("temperature", 0, "top_p", 0.1, "num_ctx", 8192);
  }

  public String modelName() {
    return model;
  }

  private List<AssignedObjectiveTemplate> parseAssignedObjectives(String rawJson) {
    try {
      var root = readOllamaJson(rawJson);
      var objectivesNode = objectivesNode(root);
      if (!objectivesNode.isArray()) {
        throw new IllegalStateException("Risposta Ollama priva dell'array objectives");
      }

      var objectives = new ArrayList<AssignedObjectiveTemplate>();
      collectAssignedObjectives(objectivesNode, objectives);

      if (objectives.isEmpty()) {
        throw new IllegalStateException("Risposta Ollama senza obiettivi assegnati validi");
      }
      return objectives;
    } catch (Exception exception) {
      throw new IllegalStateException("Impossibile interpretare la risposta Ollama: " + rawJson, exception);
    }
  }

  private void collectAssignedObjectives(JsonNode node, List<AssignedObjectiveTemplate> objectives) {
    if (node.isObject()) {
      if (looksLikeAssignedObjective(node)) {
        try {
          var objective = toAssignedObjective(node);
          if (!objective.assignments().isEmpty()) {
            objectives.add(objective);
          }
          return;
        } catch (RuntimeException ignored) {
          // Ignore partial objects and keep searching nested fragments.
        }
      }
      node.fields().forEachRemaining(entry -> collectAssignedObjectives(entry.getValue(), objectives));
      return;
    }
    if (node.isArray()) {
      node.forEach(child -> collectAssignedObjectives(child, objectives));
    }
  }

  private boolean looksLikeAssignedObjective(JsonNode node) {
    return hasAny(node, "lineCode", "line")
        && hasAny(node, "title", "t")
        && node.path("assignments").isArray()
        && !node.path("assignments").isEmpty();
  }

  private boolean hasAny(JsonNode node, String... names) {
    for (var name : names) {
      var value = node.path(name).asText(null);
      if (value != null && !value.isBlank()) {
        return true;
      }
    }
    return false;
  }

  private AssignedObjectiveTemplate toAssignedObjective(JsonNode node) {
    var lineCode = requiredText(node, "lineCode", "line");
    var title = requiredText(node, "title", "t");
    var description = optionalText(node, "description", "desc");
    var publicValue = node.has("publicValue") && !node.path("publicValue").isNull()
        ? node.path("publicValue").asBoolean()
        : null;
    var missionsPrograms = optionalText(node, "missionsPrograms", "missions");
    var assignments = new ArrayList<ObjectiveAssignmentTemplate>();
    for (var assignmentNode : node.path("assignments")) {
      try {
        assignments.add(toAssignmentTemplate(assignmentNode));
      } catch (RuntimeException ignored) {
        // Skip a malformed assignment, keep the rest of the objective.
      }
    }
    return new AssignedObjectiveTemplate(
        lineCode, title, description, publicValue, missionsPrograms.isBlank() ? null : missionsPrograms, assignments);
  }

  private ObjectiveAssignmentTemplate toAssignmentTemplate(JsonNode node) {
    var structureCode = requiredText(node, "structureCode", "assignedStructureCode", "structure");
    var actions = new ArrayList<ObjectiveActionTemplate>();
    for (var actionNode : node.path("actions")) {
      try {
        actions.add(toActionTemplate(actionNode));
      } catch (RuntimeException ignored) {
        // Skip a malformed action, keep the rest of the assignment.
      }
    }
    if (actions.isEmpty()) {
      throw new IllegalStateException("Nessuna azione valida per la struttura " + structureCode);
    }
    return new ObjectiveAssignmentTemplate(structureCode, actions);
  }

  private ObjectiveActionTemplate toActionTemplate(JsonNode node) {
    var action = optionalText(node, "action", "a");
    var indicator = requiredText(node, "indicator", "ind");
    var unit = optionalText(node, "unit");
    if (unit.isBlank()) {
      unit = "n.";
    }
    var direction = parseDirection(requiredText(node, "direction"));
    // Base target is intentionally always 0 here (never asked of the model, never invented): there is
    // no real historical data for these structures, so any number the AI produced would be fabricated.
    // 0 is the "not yet set" sentinel the frontend uses to prompt a human reviewer for the real value;
    // calibratedTarget stays 0 too until that happens (see calibratedTarget()).
    var base = 0d;
    var weight = node.path("weight").asInt(Integer.MIN_VALUE);
    if (weight == Integer.MIN_VALUE || weight < 0) {
      weight = DEFAULT_ACTION_WEIGHT;
    }
    return new ObjectiveActionTemplate(action, indicator, base, unit, direction, weight);
  }

  private void validateAssignedObjectivesForLine(
      List<AssignedObjectiveTemplate> assigned,
      StrategicLine line,
      List<StructureUnit> structures) {
    var lineCodes = Set.of(line.getCode());
    var structureCodes = structures.stream().map(StructureUnit::getCode).collect(Collectors.toSet());
    var errors = new ArrayList<String>();
    var valid = 0;

    for (var objective : assigned) {
      var lineCode = resolveKnownCode(objective.lineCode(), lineCodes);
      if (lineCode.isEmpty()) {
        errors.add("lineCode non ammesso: " + objective.lineCode());
        continue;
      }

      var resolvedAssignments = objective.assignments().stream()
          .filter(assignment -> resolveKnownCode(assignment.structureCode(), structureCodes).isPresent())
          .count();
      if (resolvedAssignments == 0) {
        errors.add("nessuna struttura ammessa per obiettivo: " + objective.title());
        continue;
      }
      valid++;
    }

    if (valid == 0) {
      throw new IllegalStateException("nessun obiettivo assegnabile a " + line.getCode() + "; " + String.join("; ", errors));
    }
  }

  private Optional<String> resolveKnownCode(String candidate, Set<String> allowedCodes) {
    if (candidate == null) {
      return Optional.empty();
    }
    if (allowedCodes.contains(candidate)) {
      return Optional.of(candidate);
    }
    return allowedCodes.stream()
        .filter(candidate::contains)
        .findFirst();
  }

  private JsonNode readOllamaJson(String rawJson) throws Exception {
    try {
      return objectMapper.readTree(rawJson);
    } catch (Exception ignored) {
      return objectMapper.readTree(extractBalancedJson(rawJson));
    }
  }

  private JsonNode objectivesNode(JsonNode root) {
    if (root.isArray()) {
      if (!root.isEmpty() && root.get(0).has("objectives")) {
        return root.get(0).path("objectives");
      }
      return root;
    }
    return root.path("objectives");
  }

  private Direction parseDirection(String value) {
    return "down".equalsIgnoreCase(value) ? Direction.down : Direction.up;
  }

  private String requiredText(JsonNode node, String... names) {
    var value = optionalText(node, names);
    if (!value.isBlank()) {
      return value;
    }
    throw new IllegalStateException("Campo obbligatorio mancante nella risposta Ollama");
  }

  private String optionalText(JsonNode node, String... names) {
    for (var name : names) {
      var value = node.path(name).asText(null);
      if (value != null && !value.isBlank()) {
        return value.trim();
      }
    }
    return "";
  }

  private String extractBalancedJson(String rawJson) {
    var start = -1;
    for (var i = 0; i < rawJson.length(); i++) {
      var c = rawJson.charAt(i);
      if (c == '{' || c == '[') {
        start = i;
        break;
      }
    }
    if (start < 0) {
      throw new IllegalStateException("Nessun JSON presente nella risposta Ollama");
    }

    var stack = new ArrayList<Character>();
    var inString = false;
    var escaped = false;
    for (var i = start; i < rawJson.length(); i++) {
      var c = rawJson.charAt(i);

      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          inString = false;
        }
        continue;
      }

      if (c == '"') {
        inString = true;
      } else if (c == '{' || c == '[') {
        stack.add(c);
      } else if (c == '}' || c == ']') {
        if (stack.isEmpty()) {
          break;
        }
        var expected = c == '}' ? '{' : '[';
        if (stack.get(stack.size() - 1) != expected) {
          break;
        }
        stack.remove(stack.size() - 1);
        if (stack.isEmpty()) {
          return rawJson.substring(start, i + 1);
        }
      }
    }

    throw new IllegalStateException("JSON Ollama non bilanciato");
  }

  private String assignmentPrompt(
      StrategicLine line,
      List<StructureUnit> structures,
      GenerateObjectivesRequest request,
      String contentContext,
      String guidelineContext) {
    return """
        Genera obiettivi SMART per il ciclo della performance PA.
        Usa SOLO il lineCode indicato. Ogni obiettivo puo' coinvolgere una o piu' DG esistenti tramite assignments;
        per ciascuna DG coinvolta definisci una o piu' azioni proprie (actions), ciascuna con indicatore, target e peso propri.

        LINEA DI INDIRIZZO:
        %s
        %s

        DG DISPONIBILI:
        %s

        Output: SOLO JSON valido, nessun markdown.
        Schema:
        {
          "objectives": [
            {
              "lineCode": "%s",
              "title": "titolo obiettivo",
              "description": "descrizione max 180 caratteri",
              "publicValue": true oppure false,
              "missionsPrograms": "missione e programma di bilancio pertinenti, es. Missione 1 - Programma 2",
              "assignments": [
                {
                  "structureCode": "%s",
                  "actions": [
                    {
                      "action": "descrizione sintetica dell'azione",
                      "indicator": "indicatore",
                      "unit": "%%|gg|km|n.",
                      "direction": "up oppure down",
                      "weight": 30
                    }
                  ]
                }
              ]
            }
          ]
        }

        Vincoli:
        - genera esattamente %d obiettivi
        - lineCode deve essere sempre "%s"
        - structureCode deve essere uno dei codici DG disponibili
        - genera da %d a %d azioni per ogni struttura coinvolta in assignments
        - weight per azione deve stare tra %d e %d, NON deve sommare a un totale fisso
        - %s
        - %s
        - unit mai vuoto, usa "n." per conteggi
        - direction solo "up" o "down"
        - indicator deve essere specifico e coerente con l'azione a cui appartiene: NON riutilizzare mai lo stesso indicator (nome o sigla) per azioni diverse in questa risposta, anche se i CRITERI METODOLOGICI qui sotto menzionano esempi, sigle o terminologia (es. "VPT"): sono principi generali sulla qualita' degli indicatori, non nomi di indicatori da copiare o riadattare
        - NON includere un valore numerico di partenza (base): non abbiamo dati storici reali per queste strutture, quindi qualunque numero inventeresti sarebbe falso. Il valore di partenza verra' inserito manualmente da un revisore umano dopo la generazione
        """.formatted(
        strategicLineForPrompt(line),
        ragContextBlock(contentContext, guidelineContext),
        structuresForPrompt(structures),
        line.getCode(),
        exampleStructureCode(structures),
        request.nPerLine(),
        line.getCode(),
        request.minActionsPerAssignment(),
        request.maxActionsPerAssignment(),
        request.actionWeightMin(),
        request.actionWeightMax(),
        multiStructureGuidance(request),
        publicValueAndMissionsGuidance(request));
  }

  private String ragContextBlock(String contentContext, String guidelineContext) {
    var sb = new StringBuilder();
    if (guidelineContext != null && !guidelineContext.isBlank()) {
      sb.append("CRITERI METODOLOGICI (regole vincolanti da normativa/linee guida caricate — rispettale "
              + "nella formulazione degli obiettivi/indicatori/pesi; sono principi generali, non sono "
              + "esempi da cui copiare nomi/sigle di indicatori — ogni indicator che generi deve restare "
              + "specifico per l'azione a cui si riferisce):\n")
          .append(compact(guidelineContext, 3000))
          .append("\n\n");
    }
    if (contentContext != null && !contentContext.isBlank()) {
      sb.append("CONTESTO DI RIFERIMENTO (materiale di supporto sul tema, recuperato da fonti caricate — "
              + "usalo solo per orientare tono, terminologia e coerenza; NON e' la fonte primaria, non "
              + "copiarlo testualmente):\n")
          .append(compact(contentContext, 4000));
    }
    return sb.toString();
  }

  private String publicValueAndMissionsGuidance(GenerateObjectivesRequest request) {
    var share = request.publicValueSharePercent();
    var publicValueGuidance = share <= 0
        ? "publicValue deve essere false per tutti gli obiettivi"
        : share >= 100
            ? "publicValue deve essere true per tutti gli obiettivi, poiche' hanno impatto diretto su cittadini/imprese"
            : "publicValue deve essere true per circa il " + share
                + "% degli obiettivi (quelli con impatto diretto su cittadini/imprese), false per gli altri";
    var missions = request.preferredMissions();
    if (missions == null || missions.isBlank()) {
      return publicValueGuidance;
    }
    return publicValueGuidance + "; privilegia, quando pertinenti, queste missioni/programmi: " + missions.trim();
  }

  private String multiStructureGuidance(GenerateObjectivesRequest request) {
    var share = request.multiStructureSharePercent();
    if (share <= 0) {
      return "usa SEMPRE una sola DG in assignments: nella realta' del PIAO ogni obiettivo appartiene a una sola struttura";
    }
    if (share < 25) {
      return "usa quasi sempre una sola DG in assignments; solo in rari casi (circa " + share
          + "% degli obiettivi) usane piu' di una, e solo se realmente coinvolte insieme";
    }
    return "usa piu' di una DG in assignments per circa il " + share
        + "% degli obiettivi, solo se realmente coinvolte insieme nello stesso obiettivo";
  }

  private String retryAssignmentPrompt(
      StrategicLine line,
      List<StructureUnit> structures,
      GenerateObjectivesRequest request,
      String errors) {
    return """
        CORREZIONE OBBLIGATORIA.
        Errori: %s
        Usa SOLO lineCode "%s".
        Usa SOLO questi structureCode: %s.
        Restituisci ESATTAMENTE %d obiettivi.
        Ogni struttura coinvolta deve avere da %d a %d azioni, con weight tra %d e %d.
        Output SOLO JSON valido: {"objectives":[{"lineCode":"%s","title":"...","description":"...","publicValue":false,"missionsPrograms":"...","assignments":[{"structureCode":"%s","actions":[{"action":"...","indicator":"...","unit":"n.","direction":"up","weight":10}]}]}]}
        NON includere un campo "base": nessun dato storico reale, lo inserisce un revisore umano dopo.

        LINEA:
        %s

        DG:
        %s
        """.formatted(
        errors,
        line.getCode(),
        structures.stream().map(StructureUnit::getCode).collect(Collectors.joining(", ")),
        request.nPerLine(),
        request.minActionsPerAssignment(),
        request.maxActionsPerAssignment(),
        request.actionWeightMin(),
        request.actionWeightMax(),
        line.getCode(),
        exampleStructureCode(structures),
        strategicLineForPrompt(line),
        structuresForPrompt(structures));
  }

  private String strategicLineForPrompt(StrategicLine line) {
    return "- " + line.getCode()
        + " | titolo: " + compact(line.getTitle(), 120)
        + " | macro-categoria: " + compact(categoryLabel(line), 160)
        + " | area: " + line.getArea()
        + " | priorita: " + line.getPriority().name()
        + " | descrizione: " + compact(line.getDescription(), 450);
  }

  private String categoryLabel(StrategicLine line) {
    var category = line.getParent() != null ? line.getParent() : line;
    return category.getCode() + " - " + category.getTitle();
  }

  // The JSON schema example needs a structureCode that actually exists, otherwise a small model tends
  // to imitate the example literally instead of picking a real code from "DG DISPONIBILI" — this used
  // to be a hardcoded "DG.50.01", a naming convention from before structures were re-imported under the
  // ORG.DG.*/ORG.SET.*/ORG.UOS.* scheme, which caused every generated assignment to reference a
  // nonexistent code and fail validateAssignedObjectivesForLine on every line, every time.
  private String exampleStructureCode(List<StructureUnit> structures) {
    return structures.isEmpty() ? "CODICE-STRUTTURA" : structures.get(0).getCode();
  }

  private String structuresForPrompt(List<StructureUnit> structures) {
    var builder = new StringBuilder();
    for (var structure : structures) {
      var dg = direzioneGeneraleOf(structure);
      var sector = sectorOf(structure);
      builder.append("- ")
          .append(structure.getCode())
          .append(" | nome: ").append(compact(structure.getName(), 70))
          .append(" | tipo: ").append(structure.getType().name())
          .append(" | DG: ").append(compact(dg.getCode() + " - " + dg.getName(), 90));
      if (sector != null && !sector.getId().equals(structure.getId())) {
        builder.append(" | settore: ").append(compact(sector.getCode() + " - " + sector.getName(), 90));
      }
      builder.append(" | area: ").append(structure.getArea())
          .append(" | indice: ").append(structure.getAveragePerformance())
          .append("\n");
    }
    return builder.toString();
  }

  private StructureUnit direzioneGeneraleOf(StructureUnit structure) {
    var current = structure;
    while (current.getParent() != null) {
      current = current.getParent();
    }
    return current;
  }

  private StructureUnit sectorOf(StructureUnit structure) {
    if (structure.getType() == it.unisa.performance.domain.StructureUnitType.settore) {
      return structure;
    }
    var parent = structure.getParent();
    if (parent != null && parent.getType() == it.unisa.performance.domain.StructureUnitType.settore) {
      return parent;
    }
    return null;
  }

  private String compact(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength) + "...";
  }

  private record OllamaGenerateRequest(
      String model,
      String prompt,
      String format,
      boolean stream,
      Map<String, Object> options) {}

  private record OllamaGenerateResponse(String response) {}
}
