package it.unisa.performance.service;

import it.unisa.performance.domain.Direction;
import it.unisa.performance.domain.GenerationRun;
import it.unisa.performance.domain.Objective;
import it.unisa.performance.domain.PerformanceTier;
import it.unisa.performance.domain.Priority;
import it.unisa.performance.domain.StrategicLine;
import it.unisa.performance.domain.StructureUnit;
import it.unisa.performance.dto.GenerateObjectivesRequest;
import it.unisa.performance.dto.GenerationResponse;
import it.unisa.performance.dto.GenerationSummaryResponse;
import it.unisa.performance.dto.StrategicLineRequest;
import it.unisa.performance.dto.StructureRequest;
import it.unisa.performance.dto.UpdateObjectiveBaseTargetRequest;
import it.unisa.performance.repository.GenerationRunRepository;
import it.unisa.performance.repository.ObjectiveRepository;
import it.unisa.performance.repository.StrategicLineRepository;
import it.unisa.performance.repository.StructureUnitRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

  private final StrategicLineRepository strategicLineRepository;
  private final StructureUnitRepository structureUnitRepository;
  private final GenerationRunRepository generationRunRepository;
  private final ObjectiveRepository objectiveRepository;
  private final ObjectiveTemplateCatalog templateCatalog;
  private final OllamaObjectiveService ollamaObjectiveService;

  public DashboardService(
      StrategicLineRepository strategicLineRepository,
      StructureUnitRepository structureUnitRepository,
      GenerationRunRepository generationRunRepository,
      ObjectiveRepository objectiveRepository,
      ObjectiveTemplateCatalog templateCatalog,
      OllamaObjectiveService ollamaObjectiveService) {
    this.strategicLineRepository = strategicLineRepository;
    this.structureUnitRepository = structureUnitRepository;
    this.generationRunRepository = generationRunRepository;
    this.objectiveRepository = objectiveRepository;
    this.templateCatalog = templateCatalog;
    this.ollamaObjectiveService = ollamaObjectiveService;
  }

  @Transactional
  public StrategicLine createStrategicLine(StrategicLineRequest request) {
    strategicLineRepository.findByCode(request.code()).ifPresent(existing -> {
      throw new IllegalArgumentException("Esiste gia una linea strategica con codice " + existing.getCode());
    });

    var line = new StrategicLine();
    line.setCode(request.code());
    line.setTitle(request.title());
    line.setArea(request.area());
    line.setPriority(request.priority());
    line.setDescription(request.desc());
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

    line.setCode(request.code());
    line.setTitle(request.title());
    line.setArea(request.area());
    line.setPriority(request.priority());
    line.setDescription(request.desc());
    return strategicLineRepository.save(line);
  }

  @Transactional
  public void deleteStrategicLine(Long id) {
    if (!strategicLineRepository.existsById(id)) {
      throw new IllegalArgumentException("Linea strategica non trovata");
    }
    strategicLineRepository.deleteById(id);
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

    applyStructureRequest(structure, request);
    return structureUnitRepository.save(structure);
  }

  @Transactional
  public void deleteStructure(Long id) {
    if (!structureUnitRepository.existsById(id)) {
      throw new IllegalArgumentException("Struttura non trovata");
    }
    structureUnitRepository.deleteById(id);
  }

  @Transactional
  public GenerationRun generateObjectives(GenerateObjectivesRequest request) {
    return generateObjectives(request, ignored -> {});
  }

  @Transactional
  public GenerationRun generateObjectives(GenerateObjectivesRequest request, Consumer<String> progress) {
    var run = new GenerationRun();
    var aiMode = "ai".equalsIgnoreCase(request.mode());
    run.setMode(aiMode ? "ollama:" + ollamaObjectiveService.modelName() : "template");
    run.setObjectivesPerLine(request.nPerLine());
    run.setStretchEccellente(request.stretchEcc());
    run.setStretchBuona(request.stretchBuo());
    run.setStretchAdeguata(request.stretchAde());
    run.setStretchRecupero(request.stretchRec());

    var lines = strategicLineRepository.findAllByOrderByCodeAsc();
    var structures = structureUnitRepository.findAllByOrderByCodeAsc();
    int progressive = 1;

    if (aiMode) {
      var linesByCode = lines.stream()
          .collect(Collectors.toMap(StrategicLine::getCode, Function.identity()));
      var structuresByCode = structures.stream()
          .collect(Collectors.toMap(StructureUnit::getCode, Function.identity()));
      var assignedObjectives = ollamaObjectiveService.generateAssigned(lines, structures, request.nPerLine(), progress);

      for (var assigned : assignedObjectives) {
        var line = findKnownLine(linesByCode, assigned.lineCode());
        var structure = findKnownStructure(structuresByCode, assigned.structureCode());
        if (line.isEmpty() || structure.isEmpty()) {
          continue;
        }
        run.addObjective(buildObjective(
            progressive++,
            line.get(),
            structure.get(),
            assigned.objective(),
            request));
      }

      if (run.getObjectives().isEmpty()) {
        throw new IllegalStateException("Ollama non ha restituito obiettivi assegnabili alle linee e strutture presenti");
      }

      return generationRunRepository.save(run);
    }

    for (var line : lines) {
      var candidateStructures = structures.stream()
          .filter(structure -> structure.getArea().equals(line.getArea()) || "Trasversale".equals(line.getArea()))
          .toList();

      var objectiveTemplates = templateCatalog.pick(line.getArea(), request.nPerLine());

      for (var template : objectiveTemplates) {
        for (var structure : candidateStructures) {
          run.addObjective(buildObjective(progressive++, line, structure, template, request));
        }
      }
    }

    return generationRunRepository.save(run);
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

  @Transactional(readOnly = true)
  public Optional<GenerationResponse> generation(Long generationId) {
    return generationRunRepository.findById(generationId)
        .map(GenerationResponse::from);
  }

  @Transactional(readOnly = true)
  public List<Objective> objectivesFor(Long generationId) {
    return objectiveRepository.findByGenerationRunIdOrderByIdAsc(generationId);
  }

  @Transactional
  public Objective updateObjectiveBaseTarget(Long objectiveId, UpdateObjectiveBaseTargetRequest request) {
    var objective = objectiveRepository.findById(objectiveId)
        .orElseThrow(() -> new IllegalArgumentException("Obiettivo non trovato"));
    return updateObjectiveBaseTarget(objective, request.baseTarget());
  }

  @Transactional
  public Objective updateObjectiveBaseTarget(
      Long generationId,
      String publicId,
      UpdateObjectiveBaseTargetRequest request) {
    var objective = objectiveRepository.findByGenerationRunIdAndPublicId(generationId, publicId)
        .orElseThrow(() -> new IllegalArgumentException("Obiettivo non trovato"));
    return updateObjectiveBaseTarget(objective, request.baseTarget());
  }

  private Objective updateObjectiveBaseTarget(Objective objective, double baseTarget) {
    objective.setBaseTarget(baseTarget);
    objective.setCalibratedTarget(calibratedTarget(
        baseTarget,
        objective.getStretch(),
        objective.getDirection()));
    return objectiveRepository.save(objective);
  }

  private double stretchFor(double average, GenerateObjectivesRequest request) {
    if (average >= 90) {
      return request.stretchEcc();
    }
    if (average >= 80) {
      return request.stretchBuo();
    }
    if (average >= 70) {
      return request.stretchAde();
    }
    return request.stretchRec();
  }

  private int weightFor(Priority priority) {
    return switch (priority) {
      case alta -> 25;
      case media -> 18;
      case bassa -> 12;
    };
  }

  private double round(double value) {
    return Math.round(value * 10.0) / 10.0;
  }

  private Objective buildObjective(
      int progressive,
      StrategicLine line,
      StructureUnit structure,
      ObjectiveTemplate template,
      GenerateObjectivesRequest request) {
    var average = structure.getAveragePerformance();
    var stretch = stretchFor(average, request);
    var calibratedTarget = calibratedTarget(template.base(), stretch, template.direction());
    var tier = PerformanceTier.fromAverage(average);

    var objective = new Objective();
    objective.setPublicId("OB." + String.format("%03d", progressive));
    objective.setLineCode(line.getCode());
    objective.setLineTitle(line.getTitle());
    objective.setArea(line.getArea());
    objective.setStructureName(structure.getName());
    objective.setStructureCode(structure.getCode());
    objective.setTier(tier);
    objective.setAveragePerformance(average);
    objective.setStretch(stretch);
    objective.setTitle(template.title());
    objective.setDescription(template.description());
    objective.setIndicator(template.indicator());
    objective.setBaseTarget(template.base());
    objective.setCalibratedTarget(calibratedTarget);
    objective.setUnit(template.unit());
    objective.setDirection(template.direction());
    objective.setWeight(weightFor(line.getPriority()));
    return objective;
  }

  private Optional<StrategicLine> findKnownLine(Map<String, StrategicLine> linesByCode, String lineCode) {
    var line = linesByCode.get(lineCode);
    if (line != null) {
      return Optional.of(line);
    }
    return linesByCode.entrySet().stream()
        .filter(entry -> lineCode != null && lineCode.contains(entry.getKey()))
        .map(Map.Entry::getValue)
        .findFirst();
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

  private void applyStructureRequest(StructureUnit structure, StructureRequest request) {
    structure.setCode(request.code());
    structure.setName(request.name());
    structure.setArea(request.area());
    structure.setPerformance2023(request.performance2023());
    structure.setPerformance2024(request.performance2024());
    structure.setPerformance2025(request.performance2025());
    structure.setAveragePerformance(round(
        request.performance2023() * 0.2
            + request.performance2024() * 0.3
            + request.performance2025() * 0.5));
  }

  private double calibratedTarget(double baseTarget, double stretch, Direction direction) {
    return direction == Direction.up
        ? round(baseTarget * (1 + stretch / 100.0))
        : round(baseTarget * (1 - stretch / 100.0));
  }
}
