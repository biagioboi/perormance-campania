package it.unisa.performance.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.unisa.performance.domain.Direction;
import it.unisa.performance.domain.StrategicLine;
import it.unisa.performance.domain.StructureUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class OllamaObjectiveService {

  private final RestClient restClient;
  private final ObjectMapper objectMapper;
  private final String model;

  public OllamaObjectiveService(
      RestClient.Builder restClientBuilder,
      ObjectMapper objectMapper,
      @Value("${ollama.base-url}") String baseUrl,
      @Value("${ollama.model}") String model) {
    this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    this.objectMapper = objectMapper;
    this.model = model;
  }

  public List<ObjectiveTemplate> generate(StrategicLine line, int count) {
    return parseObjectives(generateRaw(prompt(line, count)), count);
  }

  public List<AssignedObjectiveTemplate> generateAssigned(
      List<StrategicLine> lines,
      List<StructureUnit> structures,
      int objectivesPerLine) {
    var prompt = assignmentPrompt(lines, structures, objectivesPerLine);
    String rawResponse = "";
    RuntimeException lastException = null;

    for (var attempt = 1; attempt <= 2; attempt++) {
      rawResponse = generateRaw(prompt);
      try {
        var assigned = parseAssignedObjectives(rawResponse);
        validateHasAssignableObjectives(assigned, lines, structures);
        return assigned;
      } catch (RuntimeException exception) {
        lastException = exception;
        prompt = retryAssignmentPrompt(
            lines,
            structures,
            objectivesPerLine,
            exception.getMessage(),
            rawResponse);
      }
    }

    throw new IllegalStateException(
        "Ollama non ha restituito obiettivi assegnati validi dopo il retry: "
            + lastException.getMessage(),
        lastException);
  }

  private String generateRaw(String prompt) {
    var response = restClient.post()
        .uri("/api/generate")
        .contentType(MediaType.APPLICATION_JSON)
        .body(new OllamaGenerateRequest(model, prompt, "json", false))
        .retrieve()
        .body(OllamaGenerateResponse.class);

    if (response == null || response.response() == null || response.response().isBlank()) {
      throw new IllegalStateException("Ollama non ha restituito testo utile");
    }
    return response.response();
  }

  public String modelName() {
    return model;
  }

  private List<ObjectiveTemplate> parseObjectives(String rawJson, int count) {
    try {
      var root = readOllamaJson(rawJson);
      var objectivesNode = objectivesNode(root);
      if (!objectivesNode.isArray()) {
        throw new IllegalStateException("Risposta Ollama priva dell'array objectives");
      }

      var objectives = new ArrayList<ObjectiveTemplate>();
      for (var node : objectivesNode) {
        objectives.add(toTemplate(node));
        if (objectives.size() == count) {
          break;
        }
      }

      if (objectives.isEmpty()) {
        throw new IllegalStateException("Risposta Ollama senza obiettivi validi");
      }
      return objectives;
    } catch (Exception exception) {
      throw new IllegalStateException("Impossibile interpretare la risposta Ollama: " + rawJson, exception);
    }
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
          var lineCode = requiredText(node, "lineCode", "line");
          var structureCode = requiredText(node, "structureCode", "assignedStructureCode", "structure");
          objectives.add(new AssignedObjectiveTemplate(lineCode, structureCode, toTemplate(node)));
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
        && hasAny(node, "structureCode", "assignedStructureCode", "structure")
        && hasAny(node, "title", "t")
        && hasAny(node, "indicator", "ind");
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

  private ObjectiveTemplate toTemplate(JsonNode node) {
    var title = requiredText(node, "title", "t");
    var description = requiredText(node, "description", "desc");
    var indicator = requiredText(node, "indicator", "ind");
    var unit = optionalText(node, "unit");
    if (unit.isBlank()) {
      unit = "n.";
    }
    var direction = parseDirection(requiredText(node, "direction"));
    var base = node.path("base").asDouble(Double.NaN);
    if (!Double.isFinite(base) || base < 0) {
      throw new IllegalStateException("Target base non valido nella risposta Ollama");
    }
    if (base == 0) {
      base = fallbackBase(unit);
    }
    return new ObjectiveTemplate(title, description, indicator, base, unit, direction);
  }

  private void validateHasAssignableObjectives(
      List<AssignedObjectiveTemplate> assigned,
      List<StrategicLine> lines,
      List<StructureUnit> structures) {
    var lineCodes = lines.stream().map(StrategicLine::getCode).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    var structureCodes = structures.stream().map(StructureUnit::getCode).collect(Collectors.toSet());
    var errors = new ArrayList<String>();
    var valid = 0;

    for (var objective : assigned) {
      var lineCode = resolveKnownCode(objective.lineCode(), lineCodes);
      if (lineCode.isEmpty()) {
        errors.add("lineCode non ammesso: " + objective.lineCode());
      }

      if (resolveKnownCode(objective.structureCode(), structureCodes).isEmpty()) {
        errors.add("structureCode non ammesso: " + objective.structureCode());
      }
      if (lineCode.isPresent() && resolveKnownCode(objective.structureCode(), structureCodes).isPresent()) {
        valid++;
      }
    }

    if (valid == 0) {
      throw new IllegalStateException("nessun obiettivo assegnabile ai codici presenti; " + String.join("; ", errors));
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

  private double fallbackBase(String unit) {
    return switch (unit) {
      case "%" -> 10;
      case "gg" -> 30;
      case "km" -> 1;
      default -> 1;
    };
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

  private String prompt(StrategicLine line, int count) {
    return """
        Sei un esperto del ciclo della performance nella Pubblica Amministrazione italiana e del PIAO.

        Genera %d obiettivi operativi SMART per questa linea strategica della Regione Campania.

        Codice: %s
        Titolo: %s
        Area: %s
        Priorita: %s
        Descrizione: %s

        Rispondi solo con JSON valido, senza markdown e senza testo extra.
        Schema obbligatorio:
        {
          "objectives": [
            {
              "title": "titolo sintetico dell'obiettivo",
              "description": "descrizione operativa massimo 220 caratteri",
              "indicator": "indicatore misurabile",
              "base": 50,
              "unit": "%%, gg, km, utenti, pratiche o altra unita breve",
              "direction": "up oppure down"
            }
          ]
        }

        Regole:
        - usa base numerici realistici per una amministrazione regionale
        - direction deve essere "up" se il miglioramento aumenta il valore
        - direction deve essere "down" se il miglioramento riduce tempi, arretrati, attese o costi
        - unit non deve mai essere vuoto: usa "n." per conteggi assoluti
        - genera esattamente %d elementi nell'array objectives
        """.formatted(
        count,
        line.getCode(),
        line.getTitle(),
        line.getArea(),
        line.getPriority().name(),
        line.getDescription(),
        count);
  }

  private String assignmentPrompt(
      List<StrategicLine> lines,
      List<StructureUnit> structures,
      int objectivesPerLine) {
    return """
        Sei un esperto del ciclo della performance nella Pubblica Amministrazione italiana, PIAO, obiettivi SMART e assegnazione alle Direzioni Generali.

        Devi generare obiettivi operativi SMART gia assegnati alle Direzioni Generali competenti.
        Usa sia le linee di indirizzo sia la composizione delle Direzioni Generali con area e indici storici.

        LINEE DI INDIRIZZO:
        %s

        DIREZIONI GENERALI E PERFORMANCE STORICA:
        %s

        Rispondi solo con JSON valido, senza markdown e senza testo extra.
        Schema obbligatorio:
        {
          "objectives": [
            {
              "lineCode": "LS.01",
              "structureCode": "DG.50.01",
              "title": "titolo sintetico dell'obiettivo",
              "description": "descrizione operativa massimo 220 caratteri",
              "indicator": "indicatore misurabile",
              "base": 50,
              "unit": "%%, gg, km, utenti, pratiche o n.",
              "direction": "up oppure down"
            }
          ]
        }

        Regole obbligatorie:
        - genera esattamente %d obiettivi per ogni linea di indirizzo
        - lineCode deve essere uno dei codici presenti nelle linee di indirizzo
        - structureCode deve essere uno dei codici presenti nelle Direzioni Generali
        - assegna ogni obiettivo alla Direzione Generale piu coerente per competenza, area e performance storica
        - le linee Trasversale possono essere assegnate anche a piu Direzioni Generali diverse
        - usa base numerici realistici per una amministrazione regionale
        - direction deve essere "up" se il miglioramento aumenta il valore
        - direction deve essere "down" se il miglioramento riduce tempi, arretrati, attese o costi
        - unit non deve mai essere vuoto: usa "n." per conteggi assoluti
        """.formatted(
        strategicLinesForPrompt(lines),
        structuresForPrompt(structures),
        objectivesPerLine);
  }

  private String retryAssignmentPrompt(
      List<StrategicLine> lines,
      List<StructureUnit> structures,
      int objectivesPerLine,
      String errors,
      String previousResponse) {
    return """
        CORREZIONE OBBLIGATORIA.
        La tua risposta precedente NON e valida e non deve essere ripetuta.

        ERRORI DA CORREGGERE:
        %s

        CODICI LINEA AMMESSI, usa SOLO questi e nessun altro:
        %s

        CODICI DIREZIONE GENERALE AMMESSI, usa SOLO questi e nessun altro:
        %s

        Devi restituire ESATTAMENTE %d obiettivi per ciascun lineCode ammesso.
        Non inventare lineCode. Non inventare structureCode. Non usare codici di esempio.
        Se una linea sembra non avere una struttura perfetta, assegna la DG piu coerente tra quelle ammesse.
        Rispondi solo con JSON valido nel formato:
        {"objectives":[{"lineCode":"LS.01","structureCode":"DG.50.01","title":"...","description":"...","indicator":"...","base":1,"unit":"n.","direction":"up"}]}

        CONTESTO DA USARE:
        LINEE:
        %s

        DIREZIONI:
        %s

        RISPOSTA PRECEDENTE ERRATA, da non copiare:
        %s
        """.formatted(
        errors,
        lines.stream().map(StrategicLine::getCode).collect(Collectors.joining(", ")),
        structures.stream().map(StructureUnit::getCode).collect(Collectors.joining(", ")),
        objectivesPerLine,
        strategicLinesForPrompt(lines),
        structuresForPrompt(structures),
        truncate(previousResponse, 4000));
  }

  private String strategicLinesForPrompt(List<StrategicLine> lines) {
    var builder = new StringBuilder();
    for (var line : lines) {
      builder.append("- ")
          .append(line.getCode())
          .append(" | titolo: ").append(line.getTitle())
          .append(" | area: ").append(line.getArea())
          .append(" | priorita: ").append(line.getPriority().name())
          .append(" | descrizione: ").append(line.getDescription())
          .append('\n');
    }
    return builder.toString();
  }

  private String structuresForPrompt(List<StructureUnit> structures) {
    var builder = new StringBuilder();
    for (var structure : structures) {
      builder.append("- ")
          .append(structure.getCode())
          .append(" | nome: ").append(structure.getName())
          .append(" | area: ").append(structure.getArea())
          .append(" | perf2023: ").append(structure.getPerformance2023())
          .append(" | perf2024: ").append(structure.getPerformance2024())
          .append(" | perf2025: ").append(structure.getPerformance2025())
          .append(" | indiceMedio: ").append(structure.getAveragePerformance())
          .append('\n');
    }
    return builder.toString();
  }

  private String truncate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) {
      return value;
    }
    return value.substring(0, maxLength) + "...";
  }

  private record OllamaGenerateRequest(
      String model,
      String prompt,
      String format,
      boolean stream) {}

  private record OllamaGenerateResponse(String response) {}
}
