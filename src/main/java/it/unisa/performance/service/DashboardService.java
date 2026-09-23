package it.unisa.performance.service;

import it.unisa.performance.domain.Direction;
import it.unisa.performance.domain.GenerationRun;
import it.unisa.performance.domain.Objective;
import it.unisa.performance.domain.ObjectiveAction;
import it.unisa.performance.domain.ObjectiveStructureAssignment;
import it.unisa.performance.domain.PerformanceTier;
import it.unisa.performance.domain.Priority;
import it.unisa.performance.domain.StrategicLine;
import it.unisa.performance.domain.StrategicLineType;
import it.unisa.performance.domain.StructureUnit;
import it.unisa.performance.domain.StructureUnitType;
import it.unisa.performance.dto.ExtractedStrategicLineResponse;
import it.unisa.performance.dto.ExtractedStructureResponse;
import it.unisa.performance.dto.GenerateObjectivesRequest;
import it.unisa.performance.dto.GenerationResponse;
import it.unisa.performance.dto.GenerationSummaryResponse;
import it.unisa.performance.dto.ObjectiveResponse;
import it.unisa.performance.dto.ReviewObjectiveActionRequest;
import it.unisa.performance.dto.StrategicLineImprovementSuggestion;
import it.unisa.performance.dto.StrategicLineRequest;
import it.unisa.performance.dto.StructureRequest;
import it.unisa.performance.dto.UpdateObjectiveBaseTargetRequest;
import it.unisa.performance.repository.GenerationRunRepository;
import it.unisa.performance.repository.ObjectiveRepository;
import it.unisa.performance.repository.StrategicLineRepository;
import it.unisa.performance.repository.StructureUnitRepository;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

  private static final String DEFAULT_STRATEGIC_LINE_CODE = "LS.DEFAULT";
  private static final String DEFAULT_STRATEGIC_LINE_TITLE = "Linea strategica di default";
  private static final String DEFAULT_STRUCTURE_CODE = "DG.DEFAULT";
  private static final String DEFAULT_STRUCTURE_TITLE = "Direzione Generale di default";

  private final StrategicLineRepository strategicLineRepository;
  private final StructureUnitRepository structureUnitRepository;
  private final GenerationRunRepository generationRunRepository;
  private final ObjectiveRepository objectiveRepository;
  private final ObjectiveTemplateCatalog templateCatalog;
  private final OllamaObjectiveService ollamaObjectiveService;
  private final OllamaStrategicLineImprovementService ollamaStrategicLineImprovementService;

  public DashboardService(
      StrategicLineRepository strategicLineRepository,
      StructureUnitRepository structureUnitRepository,
      GenerationRunRepository generationRunRepository,
      ObjectiveRepository objectiveRepository,
      ObjectiveTemplateCatalog templateCatalog,
      OllamaObjectiveService ollamaObjectiveService,
      OllamaStrategicLineImprovementService ollamaStrategicLineImprovementService) {
    this.strategicLineRepository = strategicLineRepository;
    this.structureUnitRepository = structureUnitRepository;
    this.generationRunRepository = generationRunRepository;
    this.objectiveRepository = objectiveRepository;
    this.templateCatalog = templateCatalog;
    this.ollamaObjectiveService = ollamaObjectiveService;
    this.ollamaStrategicLineImprovementService = ollamaStrategicLineImprovementService;
  }

  @Transactional
  public StrategicLine createStrategicLine(StrategicLineRequest request) {
    strategicLineRepository.findByCode(request.code()).ifPresent(existing -> {
      throw new IllegalArgumentException("Esiste gia una linea strategica con codice " + existing.getCode());
    });

    var line = new StrategicLine();
    applyStrategicLineRequest(line, request);
    return strategicLineRepository.save(line);
  }

  @Transactional
  public StrategicLine updateStrategicLine(Long id, StrategicLineRequest request) {
    var line = strategicLineRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Linea strategica non trovata"));

    strategicLineRepository.findByCode(request.code())
        .filter(existing -> !existing.getId().equals(id))
        .ifPresent(existing -> {
          throw new IllegalArgumentException("Esiste gia una linea strategica con codice " + existing.getCode());
        });

    if (strategicLineRepository.existsByParentId(id) && effectiveType(request) == StrategicLineType.obiettivo_strategico) {
      throw new IllegalArgumentException(
          "Una macro-categoria con obiettivi strategici associati non puo essere convertita in obiettivo strategico");
    }

    applyStrategicLineRequest(line, request);
    return strategicLineRepository.save(line);
  }

  @Transactional
  public void deleteStrategicLine(Long id) {
    if (!strategicLineRepository.existsById(id)) {
      throw new IllegalArgumentException("Linea strategica non trovata");
    }
    if (strategicLineRepository.existsByParentId(id)) {
      throw new IllegalArgumentException(
          "Impossibile eliminare la macro-categoria: contiene obiettivi strategici associati");
    }
    strategicLineRepository.deleteById(id);
  }

  @Transactional(readOnly = true)
  public List<StrategicLineImprovementSuggestion> analyzeStrategicLineImprovements() {
    var lines = strategicLineRepository.findAllByOrderByCodeAsc();
    return ollamaStrategicLineImprovementService.analyze(lines);
  }

  @Transactional
  public List<StrategicLine> applyStrategicLineImprovements(List<StrategicLineImprovementSuggestion> corrections) {
    if (corrections == null || corrections.isEmpty()) {
      return strategicLineRepository.findAllByOrderByCodeAsc();
    }

    for (var correction : corrections) {
      if (correction.id() == null || correction.correctedText() == null || correction.correctedText().isBlank()) {
        continue;
      }
      strategicLineRepository.findById(correction.id()).ifPresent(line -> {
        line.setDescription(compact(correction.correctedText(), 1150));
        line.setTitle(compact(correction.correctedText(), 240));
        strategicLineRepository.save(line);
      });
    }

    return strategicLineRepository.findAllByOrderByCodeAsc();
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

  @Transactional
  public StructureUnit createStructure(StructureRequest request) {
    structureUnitRepository.findByCode(request.code()).ifPresent(existing -> {
      throw new IllegalArgumentException("Esiste gia una struttura con codice " + existing.getCode());
    });

    var structure = new StructureUnit();
    applyStructureRequest(structure, request);
    return structureUnitRepository.save(structure);
  }

  @Transactional
  public StructureUnit updateStructure(Long id, StructureRequest request) {
    var structure = structureUnitRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Struttura non trovata"));

    structureUnitRepository.findByCode(request.code())
        .filter(existing -> !existing.getId().equals(id))
        .ifPresent(existing -> {
          throw new IllegalArgumentException("Esiste gia una struttura con codice " + existing.getCode());
        });

    if (structureUnitRepository.existsByParentId(id) && !supportsChildren(effectiveStructureType(request))) {
      throw new IllegalArgumentException(
          "Una struttura con Settori, UOS o altre strutture figlie associate non puo essere convertita in una UOS");
    }

    applyStructureRequest(structure, request);
    return structureUnitRepository.save(structure);
  }

  @Transactional
  public void deleteStructure(Long id) {
    var structure = structureUnitRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Struttura non trovata"));

    var children = structureUnitRepository.findByParentId(id);
    if (!children.isEmpty()) {
      if (structure.getType() != StructureUnitType.direzione_generale) {
        throw new IllegalArgumentException(
            "Impossibile eliminare la struttura: contiene Settori, UOS o altre strutture figlie associate");
      }

      var unsupportedChildren = children.stream()
          .filter(child -> child.getType() != StructureUnitType.uos)
          .toList();
      if (!unsupportedChildren.isEmpty()) {
        throw new IllegalArgumentException(
            "Impossibile eliminare la Direzione Generale: contiene Settori o altre strutture figlie non eliminabili automaticamente");
      }

      structureUnitRepository.deleteAll(children);
    }

    structureUnitRepository.delete(structure);
  }

  @Transactional
  public ImportedStructureBatchResult importExtractedStructures(List<ExtractedStructureResponse> extractedStructures) {
    if (extractedStructures == null || extractedStructures.isEmpty()) {
      return new ImportedStructureBatchResult(0, 0);
    }

    var existingStructures = new ArrayList<>(structureUnitRepository.findAllByOrderByCodeAsc());
    var relation = new LinkedHashMap<String, Long>();
    int baselineCount = 0;
    int savedCount = 0;
    int nextCode = nextImportedStructureNumber(existingStructures);

    var ordered = extractedStructures.stream()
        .filter(item -> item != null && !defaultIfBlank(item.name(), "").isBlank())
        .sorted(Comparator
            .comparingInt((ExtractedStructureResponse item) -> structureTypeOrder(importedStructureType(item, extractedStructures)))
            .thenComparing(item -> defaultIfBlank(item.externalKey(), "")))
        .toList();

    for (var item : ordered) {
      var type = importedStructureType(item, extractedStructures);
      var name = defaultIfBlank(item.name(), "").trim();
      var extractedCode = sanitizeImportedStructureCode(item.code());
      var existing = findExistingStructure(existingStructures, extractedCode, name);
      var history = existing != null
          ? new double[] {existing.getPerformance2023(), existing.getPerformance2024(), existing.getPerformance2025()}
          : new double[] {97, 97, 97};
      if (existing == null) {
        baselineCount++;
      }

      Long parentId = null;
      if (type != StructureUnitType.direzione_generale) {
        var parentExternalKey = defaultIfBlank(item.parentExternalKey(), "").trim();
        if (!parentExternalKey.isBlank()) {
          parentId = relation.get(parentExternalKey);
        }
        if (parentId == null && existing != null && existing.getParent() != null) {
          parentId = existing.getParent().getId();
        }
      }

      var code = existing != null
          ? existing.getCode()
          : (!extractedCode.isBlank() ? extractedCode : generateImportedStructureCode(type, nextCode++));
      var area = existing != null
          ? existing.getArea()
          : normalizeImportedStructureArea(item.area(), name + " " + type.name());

      var request = new StructureRequest(
          code,
          name,
          type,
          parentId,
          area,
          history[0],
          history[1],
          history[2]);

      var structure = existing != null ? existing : new StructureUnit();
      applyStructureRequest(structure, request);
      var saved = structureUnitRepository.save(structure);
      relation.put(defaultIfBlank(item.externalKey(), code), saved.getId());
      if (existing == null) {
        existingStructures.add(saved);
      }
      savedCount++;
    }

    return new ImportedStructureBatchResult(savedCount, baselineCount);
  }

  @Transactional
  public int importExtractedStrategicLines(
      List<ExtractedStrategicLineResponse> items,
      Map<String, Long> relation) {
    if (items == null || items.isEmpty()) {
      return 0;
    }

    var ordered = items.stream()
        .sorted(Comparator.comparingInt(item ->
            StrategicLineType.macro_categoria.name().equals(item.type()) ? 0 : 1))
        .toList();

    int nextCategory = nextImportedCodeNumber("LS\\.(\\d+)");
    int nextObjective = nextImportedCodeNumber("OS\\.(\\d+)");
    int savedCount = 0;

    for (var item : ordered) {
      var type = StrategicLineType.obiettivo_strategico.name().equals(item.type())
          ? StrategicLineType.obiettivo_strategico
          : StrategicLineType.macro_categoria;
      var code = type == StrategicLineType.macro_categoria
          ? "LS." + String.format("%02d", nextCategory++)
          : "OS." + String.format("%02d", nextObjective++);
      var parentExternalKey = defaultIfBlank(item.parentExternalKey(), "");
      Long parentId = parentExternalKey.isBlank() ? null : relation.get(parentExternalKey);

      var request = new StrategicLineRequest(
          code,
          defaultIfBlank(item.title(), type == StrategicLineType.macro_categoria
              ? "Linea strategica estratta" : "Obiettivo strategico estratto"),
          type,
          parentId,
          defaultIfBlank(item.area(), "Trasversale"),
          sanitizeImportedPriority(item.priority()),
          defaultIfBlank(item.desc(), "—"));

      var saved = createStrategicLine(request);
      relation.put(defaultIfBlank(item.externalKey(), code), saved.getId());
      savedCount++;
    }
    return savedCount;
  }

  private Priority sanitizeImportedPriority(String value) {
    try {
      return Priority.valueOf(defaultIfBlank(value, "").trim().toLowerCase());
    } catch (IllegalArgumentException ignored) {
      return Priority.alta;
    }
  }

  private int nextImportedCodeNumber(String codePattern) {
    var regex = Pattern.compile("^" + codePattern + "$", Pattern.CASE_INSENSITIVE);
    int next = 1;
    for (var line : strategicLineRepository.findAllByOrderByCodeAsc()) {
      var matcher = regex.matcher(defaultIfBlank(line.getCode(), ""));
      if (!matcher.matches()) {
        continue;
      }
      try {
        next = Math.max(next, Integer.parseInt(matcher.group(1)) + 1);
      } catch (NumberFormatException ignored) {
        // Ignore malformed imported codes.
      }
    }
    return next;
  }

  @Transactional
  public GenerationRun startGenerationRun(GenerateObjectivesRequest request) {
    var run = new GenerationRun();
    var aiMode = "ai".equalsIgnoreCase(request.mode());
    run.setMode(aiMode ? "ollama:" + ollamaObjectiveService.modelName() : "template");
    run.setObjectivesPerLine(request.nPerLine());
    run.setStretchEccellente(request.stretchEcc());
    run.setStretchBuona(request.stretchBuo());
    run.setStretchAdeguata(request.stretchAde());
    run.setStretchRecupero(request.stretchRec());
    return generationRunRepository.save(run);
  }

  @Transactional(readOnly = true)
  public List<StrategicLine> generationLineTargets() {
    return generationTargets(strategicLineRepository.findAllByOrderByCodeAsc());
  }

  @Transactional(readOnly = true)
  public List<StructureUnit> generationStructureTargets() {
    return generationStructures(structureUnitRepository.findAllByOrderByCodeAsc());
  }

  /** Persists one strategic line's AI-generated objectives immediately, so a slow/interrupted run doesn't lose earlier progress. Returns how many objectives were actually saved. */
  @Transactional
  public int appendAssignedObjectives(
      Long runId, StrategicLine line, List<AssignedObjectiveTemplate> assigned, GenerateObjectivesRequest request) {
    var run = generationRunRepository.findById(runId)
        .orElseThrow(() -> new IllegalArgumentException("Simulazione non trovata"));
    var structuresByCode = structureUnitRepository.findAllByOrderByCodeAsc().stream()
        .collect(Collectors.toMap(StructureUnit::getCode, Function.identity()));
    var random = new Random();
    var progressive = run.getObjectives().size() + 1;
    var added = 0;

    for (var item : assigned) {
      List<StructureAssignmentTemplate> resolvedAssignments = new ArrayList<>();
      for (var assignmentTemplate : item.assignments()) {
        var structure = findKnownStructure(structuresByCode, assignmentTemplate.structureCode());
        if (structure.isEmpty()) {
          continue;
        }
        var clampedActions = clampActions(assignmentTemplate.actions(), request);
        if (clampedActions.size() < request.minActionsPerAssignment()) {
          continue;
        }
        resolvedAssignments.add(new StructureAssignmentTemplate(structure.get(), clampedActions));
      }
      resolvedAssignments = enforceMultiStructureLimit(resolvedAssignments, request, random);
      if (resolvedAssignments.isEmpty()) {
        continue;
      }
      run.addObjective(buildObjective(
          progressive++, line, item.title(), item.description(), item.publicValue(), item.missionsPrograms(), resolvedAssignments, request));
      added++;
    }

    generationRunRepository.save(run);
    return added;
  }

  /** Template mode's per-line fan-out (one objective per candidate structure), persisted immediately like the AI path. */
  @Transactional
  public int appendTemplateObjectivesForLine(Long runId, StrategicLine line, GenerateObjectivesRequest request) {
    var run = generationRunRepository.findById(runId)
        .orElseThrow(() -> new IllegalArgumentException("Simulazione non trovata"));
    var structures = generationStructures(structureUnitRepository.findAllByOrderByCodeAsc());
    var candidateStructures = structures.stream()
        .filter(structure -> structure.getArea().equals(line.getArea()) || "Trasversale".equals(line.getArea()))
        .toList();
    var objectiveTemplates = templateCatalog.pick(line.getArea(), request.nPerLine());
    var progressive = run.getObjectives().size() + 1;
    var added = 0;

    for (var template : objectiveTemplates) {
      for (var structure : candidateStructures) {
        var assignment = new StructureAssignmentTemplate(structure, template.actions());
        run.addObjective(buildObjective(
            progressive++, line, template.title(), template.description(), null, null, List.of(assignment), request));
        added++;
      }
    }

    generationRunRepository.save(run);
    return added;
  }

  @Transactional(readOnly = true)
  public Optional<GenerationResponse> latestGeneration() {
    return generationRunRepository.findTopByOrderByCreatedAtDesc()
        .map(GenerationResponse::from);
  }

  @Transactional(readOnly = true)
  public List<GenerationSummaryResponse> generations() {
    return generationRunRepository.findAllByOrderByCreatedAtDesc().stream()
        .map(GenerationSummaryResponse::from)
        .toList();
  }

  @Transactional
  public void deleteGeneration(Long id) {
    if (!generationRunRepository.existsById(id)) {
      throw new IllegalArgumentException("Simulazione non trovata");
    }
    generationRunRepository.deleteById(id);
  }

  @Transactional(readOnly = true)
  public Optional<GenerationResponse> generation(Long generationId) {
    return generationRunRepository.findById(generationId)
        .map(GenerationResponse::from);
  }

  @Transactional(readOnly = true)
  public List<ObjectiveResponse> objectivesFor(Long generationId) {
    return objectiveRepository.findByGenerationRunIdOrderByIdAsc(generationId).stream()
        .map(ObjectiveResponse::from)
        .toList();
  }

  @Transactional
  public ObjectiveResponse updateObjectiveBaseTarget(Long objectiveId, UpdateObjectiveBaseTargetRequest request) {
    var objective = objectiveRepository.findById(objectiveId)
        .orElseThrow(() -> new IllegalArgumentException("Obiettivo non trovato"));
    return ObjectiveResponse.from(
        updateObjectiveBaseTarget(objective, request.assignmentIndex(), request.actionIndex(), request.baseTarget()));
  }

  @Transactional
  public ObjectiveResponse updateObjectiveBaseTarget(
      Long generationId,
      String publicId,
      UpdateObjectiveBaseTargetRequest request) {
    var objective = objectiveRepository.findByGenerationRunIdAndPublicId(generationId, publicId)
        .orElseThrow(() -> new IllegalArgumentException("Obiettivo non trovato"));
    return ObjectiveResponse.from(
        updateObjectiveBaseTarget(objective, request.assignmentIndex(), request.actionIndex(), request.baseTarget()));
  }

  private Objective updateObjectiveBaseTarget(Objective objective, int assignmentIndex, int actionIndex, double baseTarget) {
    var assignments = objective.getAssignments();
    if (assignmentIndex < 0 || assignmentIndex >= assignments.size()) {
      throw new IllegalArgumentException("Struttura non trovata per l'obiettivo " + objective.getPublicId());
    }
    var assignment = assignments.get(assignmentIndex);
    var actions = assignment.getActions();
    if (actionIndex < 0 || actionIndex >= actions.size()) {
      throw new IllegalArgumentException("Azione non trovata per l'obiettivo " + objective.getPublicId());
    }
    var current = actions.get(actionIndex);
    var calibrated = calibratedTarget(baseTarget, assignment.getStretch(), current.getDirection());
    actions.set(actionIndex, new ObjectiveAction(
        current.getAction(), current.getIndicator(), baseTarget, calibrated, current.getUnit(), current.getDirection(),
        current.getWeight(), current.getApproved(), current.getReviewScore()));
    return objectiveRepository.save(objective);
  }

  public ObjectiveResponse reviewObjectiveAction(Long objectiveId, ReviewObjectiveActionRequest request) {
    var objective = objectiveRepository.findById(objectiveId)
        .orElseThrow(() -> new IllegalArgumentException("Obiettivo non trovato"));
    return ObjectiveResponse.from(
        reviewObjectiveAction(objective, request.assignmentIndex(), request.actionIndex(), request.approved(), request.score()));
  }

  @Transactional
  public ObjectiveResponse reviewObjectiveAction(
      Long generationId, String publicId, ReviewObjectiveActionRequest request) {
    var objective = objectiveRepository.findByGenerationRunIdAndPublicId(generationId, publicId)
        .orElseThrow(() -> new IllegalArgumentException("Obiettivo non trovato"));
    return ObjectiveResponse.from(
        reviewObjectiveAction(objective, request.assignmentIndex(), request.actionIndex(), request.approved(), request.score()));
  }

  private Objective reviewObjectiveAction(
      Objective objective, int assignmentIndex, int actionIndex, Boolean approved, Integer score) {
    var assignments = objective.getAssignments();
    if (assignmentIndex < 0 || assignmentIndex >= assignments.size()) {
      throw new IllegalArgumentException("Struttura non trovata per l'obiettivo " + objective.getPublicId());
    }
    var assignment = assignments.get(assignmentIndex);
    var actions = assignment.getActions();
    if (actionIndex < 0 || actionIndex >= actions.size()) {
      throw new IllegalArgumentException("Azione non trovata per l'obiettivo " + objective.getPublicId());
    }
    if (score != null && (score < 0 || score > 100)) {
      throw new IllegalArgumentException("Il punteggio deve essere tra 0 e 100");
    }
    var current = actions.get(actionIndex);
    actions.set(actionIndex, new ObjectiveAction(
        current.getAction(), current.getIndicator(), current.getBaseTarget(), current.getCalibratedTarget(),
        current.getUnit(), current.getDirection(), current.getWeight(), approved, score));
    return objectiveRepository.save(objective);
  }

  private double stretchFor(double average, GenerateObjectivesRequest request) {
    if (average >= 99) {
      return request.stretchEcc();
    }
    if (average >= 98) {
      return request.stretchBuo();
    }
    if (average >= 97) {
      return request.stretchAde();
    }
    return request.stretchRec();
  }

  private double round(double value) {
    return Math.round(value * 10.0) / 10.0;
  }

  private record StructureAssignmentTemplate(StructureUnit structure, List<ObjectiveActionTemplate> actions) {}

  // Deterministic guardrails applied on top of the LLM output: the prompt asks for these bounds too,
  // but real-world PIAO data showed models don't always follow numeric constraints reliably, so we
  // enforce them in code rather than trust generation alone.
  private List<ObjectiveActionTemplate> clampActions(List<ObjectiveActionTemplate> actions, GenerateObjectivesRequest request) {
    var limited = actions.size() > request.maxActionsPerAssignment()
        ? actions.subList(0, request.maxActionsPerAssignment())
        : actions;
    return limited.stream()
        .map(action -> new ObjectiveActionTemplate(
            action.action(),
            action.indicator(),
            action.base(),
            action.unit(),
            action.direction(),
            clampWeight(action.weight(), request)))
        .toList();
  }

  private int clampWeight(int weight, GenerateObjectivesRequest request) {
    return Math.max(request.actionWeightMin(), Math.min(request.actionWeightMax(), weight));
  }

  // Real OBSA data from the PIAO never spans multiple structures; multiStructureSharePercent lets the
  // user allow it anyway, applied as a per-objective coin flip rather than a prompt-only suggestion.
  private List<StructureAssignmentTemplate> enforceMultiStructureLimit(
      List<StructureAssignmentTemplate> assignments, GenerateObjectivesRequest request, Random random) {
    if (assignments.size() <= 1) {
      return assignments;
    }
    if (random.nextInt(100) < request.multiStructureSharePercent()) {
      return assignments;
    }
    return List.of(assignments.get(0));
  }

  private Objective buildObjective(
      int progressive,
      StrategicLine line,
      String title,
      String description,
      Boolean publicValue,
      String missionsPrograms,
      List<StructureAssignmentTemplate> assignmentTemplates,
      GenerateObjectivesRequest request) {
    var category = categoryOf(line);

    var objective = new Objective();
    objective.setPublicId("OB." + String.format("%03d", progressive));
    objective.setLineCode(line.getCode());
    objective.setLineTitle(line.getTitle());
    objective.setCategoryCode(category.getCode());
    objective.setCategoryTitle(category.getTitle());
    objective.setArea(line.getArea());
    objective.setTitle(title);
    objective.setDescription(description);
    objective.setPublicValue(publicValue);
    objective.setMissionsPrograms(missionsPrograms);

    for (var assignmentTemplate : assignmentTemplates) {
      var structure = assignmentTemplate.structure();
      var average = structure.getAveragePerformance();
      var stretch = stretchFor(average, request);

      var assignment = new ObjectiveStructureAssignment();
      assignment.setObjective(objective);
      assignment.setStructureUnit(structure);
      assignment.setTier(PerformanceTier.fromAverage(average));
      assignment.setAveragePerformance(average);
      assignment.setStretch(stretch);

      for (var actionTemplate : assignmentTemplate.actions()) {
        var calibrated = calibratedTarget(actionTemplate.base(), stretch, actionTemplate.direction());
        assignment.getActions().add(new ObjectiveAction(
            actionTemplate.action(),
            actionTemplate.indicator(),
            actionTemplate.base(),
            calibrated,
            actionTemplate.unit(),
            actionTemplate.direction(),
            actionTemplate.weight()));
      }
      objective.getAssignments().add(assignment);
    }
    return objective;
  }

  private Optional<StructureUnit> findKnownStructure(
      Map<String, StructureUnit> structuresByCode,
      String structureCode) {
    var structure = structuresByCode.get(structureCode);
    if (structure != null) {
      return Optional.of(structure);
    }
    return structuresByCode.entrySet().stream()
        .filter(entry -> structureCode != null && structureCode.contains(entry.getKey()))
        .map(Map.Entry::getValue)
        .findFirst();
  }

  private List<StructureUnit> generationStructures(List<StructureUnit> structures) {
    var parentIds = structures.stream()
        .filter(structure -> structure.getParent() != null)
        .map(structure -> structure.getParent().getId())
        .collect(Collectors.toSet());
    var leaves = structures.stream()
        .filter(structure -> !parentIds.contains(structure.getId()))
        .toList();
    return leaves.isEmpty() ? structures : leaves;
  }

  private List<StrategicLine> generationTargets(List<StrategicLine> lines) {
    var strategicObjectives = lines.stream()
        .filter(line -> line.getType() == StrategicLineType.obiettivo_strategico)
        .toList();
    if (!strategicObjectives.isEmpty()) {
      return strategicObjectives;
    }
    return lines.stream()
        .filter(line -> line.getType() == StrategicLineType.macro_categoria)
        .toList();
  }

  private StrategicLine categoryOf(StrategicLine line) {
    return line.getParent() != null ? line.getParent() : line;
  }

  private void applyStrategicLineRequest(StrategicLine line, StrategicLineRequest request) {
    var type = effectiveType(request);
    var parent = resolveParent(type, request.parentId(), line.getId());

    if (type == StrategicLineType.macro_categoria && parent != null) {
      throw new IllegalArgumentException("Una macro-categoria non puo avere una categoria padre");
    }
    if (parent != null && parent.getType() != StrategicLineType.macro_categoria) {
      throw new IllegalArgumentException(
          "La categoria padre deve essere una macro-categoria strategica");
    }

    line.setCode(request.code());
    line.setTitle(request.title());
    line.setType(type);
    line.setParent(parent);
    line.setArea(request.area());
    line.setPriority(request.priority());
    line.setDescription(request.desc());
  }

  private StrategicLineType effectiveType(StrategicLineRequest request) {
    if (request.type() != null) {
      return request.type();
    }
    return request.parentId() != null
        ? StrategicLineType.obiettivo_strategico
        : StrategicLineType.macro_categoria;
  }

  private StrategicLine resolveParent(StrategicLineType type, Long parentId, Long currentLineId) {
    if (parentId == null) {
      if (type != StrategicLineType.obiettivo_strategico) {
        return null;
      }
      var defaultCategory = defaultStrategicCategory();
      if (currentLineId != null && currentLineId.equals(defaultCategory.getId())) {
        throw new IllegalArgumentException(
            "La linea strategica di default non puo essere convertita in obiettivo strategico figlio di se stessa");
      }
      return defaultCategory;
    }
    if (currentLineId != null && currentLineId.equals(parentId)) {
      throw new IllegalArgumentException("Una linea strategica non puo essere categoria di se stessa");
    }
    return strategicLineRepository.findById(parentId)
        .orElseThrow(() -> new IllegalArgumentException("Macro-categoria strategica non trovata"));
  }

  private StructureUnitType effectiveStructureType(StructureRequest request) {
    if (request.type() != null) {
      return request.type();
    }
    var code = request.code() == null ? "" : request.code().trim().toUpperCase();
    if (code.startsWith("DG")) {
      return StructureUnitType.direzione_generale;
    }
    if (code.startsWith("SET")) {
      return StructureUnitType.settore;
    }
    return request.parentId() != null ? StructureUnitType.uos : StructureUnitType.direzione_generale;
  }

  private boolean supportsChildren(StructureUnitType type) {
    return type == StructureUnitType.direzione_generale || type == StructureUnitType.settore;
  }

  private StructureUnit resolveStructureParent(StructureUnitType type, Long parentId, Long currentId) {
    if (type == StructureUnitType.direzione_generale) {
      if (parentId != null) {
        throw new IllegalArgumentException("Una Direzione Generale non puo avere una struttura padre");
      }
      return null;
    }

    if (parentId == null) {
      return defaultStructureGroup();
    }

    var parent = structureUnitRepository.findById(parentId)
        .orElseThrow(() -> new IllegalArgumentException("Struttura padre non trovata"));

    if (currentId != null && parent.getId().equals(currentId)) {
      throw new IllegalArgumentException("Una struttura non puo essere padre di se stessa");
    }

    if (type == StructureUnitType.settore) {
      if (parent.getType() != StructureUnitType.direzione_generale) {
        throw new IllegalArgumentException("I Settori possono essere collegati solo a una Direzione Generale");
      }
      return parent;
    }

    if (parent.getType() != StructureUnitType.direzione_generale && parent.getType() != StructureUnitType.settore) {
      throw new IllegalArgumentException("Le UOS possono essere collegate solo a una Direzione Generale o a un Settore");
    }
    return parent;
  }

  private StructureUnit defaultStructureGroup() {
    return structureUnitRepository.findByCode(DEFAULT_STRUCTURE_CODE)
        .orElseGet(() -> {
          var structure = new StructureUnit();
          structure.setCode(DEFAULT_STRUCTURE_CODE);
          structure.setName(DEFAULT_STRUCTURE_TITLE);
          structure.setType(StructureUnitType.direzione_generale);
          structure.setArea("Trasversale");
          structure.setPerformance2023(97);
          structure.setPerformance2024(97);
          structure.setPerformance2025(97);
          structure.setAveragePerformance(97);
          return structureUnitRepository.save(structure);
        });
  }

  private StrategicLine defaultStrategicCategory() {
    return strategicLineRepository.findByCode(DEFAULT_STRATEGIC_LINE_CODE)
        .orElseGet(() -> {
          var line = new StrategicLine();
          line.setCode(DEFAULT_STRATEGIC_LINE_CODE);
          line.setTitle(DEFAULT_STRATEGIC_LINE_TITLE);
          line.setType(StrategicLineType.macro_categoria);
          line.setArea("Trasversale");
          line.setPriority(Priority.media);
          line.setDescription(
              "Contiene gli obiettivi strategici estratti o inseriti senza una macro-categoria esplicita.");
          return strategicLineRepository.save(line);
        });
  }

  private void applyStructureRequest(StructureUnit structure, StructureRequest request) {
    var type = effectiveStructureType(request);
    var parent = resolveStructureParent(type, request.parentId(), structure.getId());

    structure.setCode(request.code());
    structure.setName(request.name());
    structure.setType(type);
    structure.setParent(parent);
    structure.setArea(request.area());
    structure.setPerformance2023(request.performance2023());
    structure.setPerformance2024(request.performance2024());
    structure.setPerformance2025(request.performance2025());
    structure.setAveragePerformance(round(
        request.performance2023() * 0.2
            + request.performance2024() * 0.3
            + request.performance2025() * 0.5));
  }

  private StructureUnitType importedStructureType(
      ExtractedStructureResponse item,
      List<ExtractedStructureResponse> allItems) {
    var explicit = defaultIfBlank(item.type(), "").trim().toLowerCase();
    boolean hasChildren = allItems.stream()
        .anyMatch(candidate -> defaultIfBlank(candidate.parentExternalKey(), "")
            .equals(defaultIfBlank(item.externalKey(), "")));
    if ("direzione_generale".equals(explicit)) {
      return StructureUnitType.direzione_generale;
    }
    if ("settore".equals(explicit)) {
      return StructureUnitType.settore;
    }
    if ("uos".equals(explicit)) {
      return StructureUnitType.uos;
    }
    if (hasChildren) {
      return defaultIfBlank(item.parentExternalKey(), "").isBlank()
          ? StructureUnitType.direzione_generale
          : StructureUnitType.settore;
    }
    if (!defaultIfBlank(item.parentExternalKey(), "").isBlank()) {
      return StructureUnitType.uos;
    }
    return StructureUnitType.direzione_generale;
  }

  private int structureTypeOrder(StructureUnitType type) {
    return switch (type) {
      case direzione_generale -> 0;
      case settore -> 1;
      default -> 2;
    };
  }

  private StructureUnit findExistingStructure(List<StructureUnit> structures, String code, String name) {
    var normalizedCode = normalizeImportedText(code);
    var normalizedName = normalizeImportedText(name);
    for (var structure : structures) {
      if (!normalizedCode.isBlank() && normalizedCode.equals(normalizeImportedText(structure.getCode()))) {
        return structure;
      }
      if (!normalizedName.isBlank() && normalizedName.equals(normalizeImportedText(structure.getName()))) {
        return structure;
      }
    }
    return null;
  }

  private int nextImportedStructureNumber(List<StructureUnit> structures) {
    int next = 1;
    for (var structure : structures) {
      var code = defaultIfBlank(structure.getCode(), "");
      var lastDot = code.lastIndexOf('.');
      if (lastDot < 0 || lastDot == code.length() - 1) {
        continue;
      }
      var suffix = code.substring(lastDot + 1);
      if (!suffix.chars().allMatch(Character::isDigit)) {
        continue;
      }
      try {
        next = Math.max(next, Integer.parseInt(suffix) + 1);
      } catch (NumberFormatException ignored) {
        // Ignore malformed imported codes.
      }
    }
    return next;
  }

  private String generateImportedStructureCode(StructureUnitType type, int index) {
    return "ORG." + importedStructurePrefix(type) + "." + String.format("%03d", index);
  }

  private String importedStructurePrefix(StructureUnitType type) {
    return switch (type) {
      case direzione_generale -> "DG";
      case settore -> "SET";
      case uos -> "UOS";
      default -> "STR";
    };
  }

  private String sanitizeImportedStructureCode(String value) {
    var code = defaultIfBlank(value, "").trim().replaceAll("\\s+", " ");
    var officialLike = code.matches("(?i)^[A-Z0-9.\\-/ ]+$") && (code.matches(".*\\d.*") || code.contains("."));
    return officialLike ? code.substring(0, Math.min(code.length(), 32)) : "";
  }

  private String normalizeImportedStructureArea(String area, String fallbackText) {
    var value = defaultIfBlank(area, "").trim();
    var allowed = List.of(
        "Trasversale",
        "Digitalizzazione",
        "Sanita",
        "Ambiente",
        "Lavoro e Formazione",
        "Cultura e Turismo",
        "Politiche Sociali",
        "Mobilita",
        "Sviluppo Economico");
    return allowed.contains(value) ? value : inferImportedStructureArea(value + " " + defaultIfBlank(fallbackText, ""));
  }

  private String inferImportedStructureArea(String text) {
    var value = normalizeImportedText(text);
    if (containsAny(value, "digital", "semplificazione", "procediment", "servizi online", "piattaforma")) {
      return "Digitalizzazione";
    }
    if (containsAny(value, "sanita", "salute", "screening", "liste d attesa", "asl", "ospedal")) {
      return "Sanita";
    }
    if (containsAny(value, "ambiente", "rifiuti", "ecologic", "bonifica", "suolo", "energia")) {
      return "Ambiente";
    }
    if (containsAny(value, "lavoro", "formazione", "competenze", "occupazione", "cpi")) {
      return "Lavoro e Formazione";
    }
    if (containsAny(value, "cultura", "turismo", "muse", "patrimonio", "visitatori")) {
      return "Cultura e Turismo";
    }
    if (containsAny(value, "social", "anziani", "disabilita", "fragilita", "assistenza")) {
      return "Politiche Sociali";
    }
    if (containsAny(value, "mobilita", "trasporto", "tpl", "ciclab", "ferro")) {
      return "Mobilita";
    }
    if (containsAny(value, "sviluppo economico", "impres", "pmi", "contributi", "innovazione")) {
      return "Sviluppo Economico";
    }
    return "Trasversale";
  }

  private boolean containsAny(String value, String... needles) {
    for (var needle : needles) {
      if (value.contains(needle)) {
        return true;
      }
    }
    return false;
  }

  private String normalizeImportedText(String value) {
    return Normalizer.normalize(defaultIfBlank(value, ""), Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .replace('’', '\'')
        .replace('`', '\'')
        .trim()
        .toLowerCase()
        .replaceAll("\\s+", " ");
  }

  private String defaultIfBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private double calibratedTarget(double baseTarget, double stretch, Direction direction) {
    return direction == Direction.up
        ? round(baseTarget * (1 + stretch / 100.0))
        : round(baseTarget * (1 - stretch / 100.0));
  }


  public record ImportedStructureBatchResult(int savedCount, int baselineCount) {}
}
