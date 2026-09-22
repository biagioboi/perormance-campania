package it.unisa.performance.controller;

import it.unisa.performance.dto.DashboardTaskResponse;
import it.unisa.performance.dto.ExtractedStrategicLineResponse;
import it.unisa.performance.dto.ExtractedStructureResponse;
import it.unisa.performance.dto.GenerateObjectivesRequest;
import it.unisa.performance.dto.GenerationJobResponse;
import it.unisa.performance.dto.GenerationResponse;
import it.unisa.performance.dto.GenerationSummaryResponse;
import it.unisa.performance.dto.ObjectiveResponse;
import it.unisa.performance.dto.OrganigramImportJobResponse;
import it.unisa.performance.dto.StrategicLineImprovementSuggestion;
import it.unisa.performance.dto.StrategicLineImportJobResponse;
import it.unisa.performance.dto.StrategicLineRequest;
import it.unisa.performance.dto.StrategicLineResponse;
import it.unisa.performance.dto.StructureRequest;
import it.unisa.performance.dto.StructureResponse;
import it.unisa.performance.dto.UpdateObjectiveBaseTargetRequest;
import it.unisa.performance.repository.StrategicLineRepository;
import it.unisa.performance.repository.StructureUnitRepository;
import it.unisa.performance.service.DashboardService;
import it.unisa.performance.service.DashboardTaskService;
import it.unisa.performance.service.DemoDataService;
import it.unisa.performance.service.GenerationJobService;
import it.unisa.performance.service.OrganigramImportJobService;
import it.unisa.performance.service.PdfExtractionService;
import it.unisa.performance.service.StrategicLineImportJobService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class DashboardController {

  private final StrategicLineRepository strategicLineRepository;
  private final StructureUnitRepository structureUnitRepository;
  private final DashboardService dashboardService;
  private final DashboardTaskService dashboardTaskService;
  private final DemoDataService demoDataService;
  private final PdfExtractionService pdfExtractionService;
  private final GenerationJobService generationJobService;
  private final OrganigramImportJobService organigramImportJobService;
  private final StrategicLineImportJobService strategicLineImportJobService;

  public DashboardController(
      StrategicLineRepository strategicLineRepository,
      StructureUnitRepository structureUnitRepository,
      DashboardService dashboardService,
      DashboardTaskService dashboardTaskService,
      DemoDataService demoDataService,
      PdfExtractionService pdfExtractionService,
      GenerationJobService generationJobService,
      OrganigramImportJobService organigramImportJobService,
      StrategicLineImportJobService strategicLineImportJobService) {
    this.strategicLineRepository = strategicLineRepository;
    this.structureUnitRepository = structureUnitRepository;
    this.dashboardService = dashboardService;
    this.dashboardTaskService = dashboardTaskService;
    this.demoDataService = demoDataService;
    this.pdfExtractionService = pdfExtractionService;
    this.generationJobService = generationJobService;
    this.organigramImportJobService = organigramImportJobService;
    this.strategicLineImportJobService = strategicLineImportJobService;
  }

  @GetMapping("/tasks")
  public List<DashboardTaskResponse> tasks() {
    return dashboardTaskService.tasks();
  }

  @GetMapping("/strategic-lines")
  public List<StrategicLineResponse> strategicLines() {
    return strategicLineRepository.findAllByOrderByCodeAsc().stream()
        .map(StrategicLineResponse::from)
        .toList();
  }

  @PostMapping("/strategic-lines")
  public StrategicLineResponse createStrategicLine(@Valid @RequestBody StrategicLineRequest request) {
    return StrategicLineResponse.from(dashboardService.createStrategicLine(request));
  }

  @PutMapping("/strategic-lines/{id}")
  public StrategicLineResponse updateStrategicLine(
      @PathVariable Long id,
      @Valid @RequestBody StrategicLineRequest request) {
    return StrategicLineResponse.from(dashboardService.updateStrategicLine(id, request));
  }

  @DeleteMapping("/strategic-lines/{id}")
  public ResponseEntity<Void> deleteStrategicLine(@PathVariable Long id) {
    dashboardService.deleteStrategicLine(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/strategic-lines/demo-reset")
  public List<StrategicLineResponse> resetStrategicLines() {
    return demoDataService.resetStrategicLines().stream()
        .map(StrategicLineResponse::from)
        .toList();
  }

  @PostMapping("/strategic-lines/improve/analyze")
  public List<StrategicLineImprovementSuggestion> analyzeStrategicLineImprovements() {
    return dashboardService.analyzeStrategicLineImprovements();
  }

  @PostMapping("/strategic-lines/improve/apply")
  public List<StrategicLineResponse> applyStrategicLineImprovements(
      @RequestBody List<StrategicLineImprovementSuggestion> corrections) {
    return dashboardService.applyStrategicLineImprovements(corrections).stream()
        .map(StrategicLineResponse::from)
        .toList();
  }

  @PostMapping(value = "/strategic-lines/extract-pdf", consumes = "multipart/form-data")
  public List<ExtractedStrategicLineResponse> extractStrategicLinesFromPdf(@RequestParam("data") MultipartFile file) throws IOException {
    return pdfExtractionService.extractStrategicLines(file);
  }

  @PostMapping(value = "/strategic-lines/import-pdf", consumes = "multipart/form-data")
  public StrategicLineImportJobResponse startStrategicLineImportJob(@RequestParam("data") MultipartFile file) throws IOException {
    return strategicLineImportJobService.start(file);
  }

  @GetMapping("/strategic-lines/import-pdf/{jobId}")
  public StrategicLineImportJobResponse strategicLineImportJobStatus(@PathVariable String jobId) {
    return strategicLineImportJobService.status(jobId);
  }

  @PostMapping(value = "/structures/extract-organigram-pdf", consumes = "multipart/form-data")
  public List<ExtractedStructureResponse> extractStructuresFromOrganigramPdf(@RequestParam("data") MultipartFile file) throws IOException {
    return pdfExtractionService.extractOrganigramStructures(file);
  }

  @PostMapping(value = "/structures/import-organigram-pdf", consumes = "multipart/form-data")
  public OrganigramImportJobResponse startOrganigramImportJob(@RequestParam("data") MultipartFile file) throws IOException {
    return organigramImportJobService.start(file);
  }

  @GetMapping("/structures/import-organigram-pdf/{jobId}")
  public OrganigramImportJobResponse organigramImportJobStatus(@PathVariable String jobId) {
    return organigramImportJobService.status(jobId);
  }

  @GetMapping("/structures")
  public List<StructureResponse> structures() {
    return structureUnitRepository.findAllByOrderByCodeAsc().stream()
        .map(StructureResponse::from)
        .toList();
  }

  @PostMapping("/structures")
  public StructureResponse createStructure(@Valid @RequestBody StructureRequest request) {
    return StructureResponse.from(dashboardService.createStructure(request));
  }

  @PutMapping("/structures/{id}")
  public StructureResponse updateStructure(
      @PathVariable Long id,
      @Valid @RequestBody StructureRequest request) {
    return StructureResponse.from(dashboardService.updateStructure(id, request));
  }

  @DeleteMapping("/structures/{id}")
  public ResponseEntity<Void> deleteStructure(@PathVariable Long id) {
    dashboardService.deleteStructure(id);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/generation-jobs")
  public GenerationJobResponse startGenerationJob(@Valid @RequestBody GenerateObjectivesRequest request) {
    return generationJobService.start(request);
  }

  @GetMapping("/generation-jobs/{jobId}")
  public GenerationJobResponse generationJobStatus(@PathVariable String jobId) {
    return generationJobService.status(jobId);
  }

  @GetMapping("/generations")
  public List<GenerationSummaryResponse> generations() {
    return dashboardService.generations();
  }

  @GetMapping("/generations/latest")
  public ResponseEntity<GenerationResponse> latestGeneration() {
    return dashboardService.latestGeneration()
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @GetMapping("/generations/{generationId}")
  public ResponseEntity<GenerationResponse> generation(@PathVariable Long generationId) {
    return dashboardService.generation(generationId)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @DeleteMapping("/generations/{generationId}")
  public ResponseEntity<Void> deleteGeneration(@PathVariable Long generationId) {
    dashboardService.deleteGeneration(generationId);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/generations/{generationId}/objectives")
  public List<ObjectiveResponse> objectives(@PathVariable Long generationId) {
    return dashboardService.objectivesFor(generationId);
  }

  @PutMapping("/objectives/{objectiveId}/base-target")
  public ObjectiveResponse updateObjectiveBaseTarget(
      @PathVariable Long objectiveId,
      @Valid @RequestBody UpdateObjectiveBaseTargetRequest request) {
    return dashboardService.updateObjectiveBaseTarget(objectiveId, request);
  }

  @PutMapping("/generations/{generationId}/objectives/{publicId}/base-target")
  public ObjectiveResponse updateObjectiveBaseTargetByPublicId(
      @PathVariable Long generationId,
      @PathVariable String publicId,
      @Valid @RequestBody UpdateObjectiveBaseTargetRequest request) {
    return dashboardService.updateObjectiveBaseTarget(generationId, publicId, request);
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<String> handleBadRequest(IllegalArgumentException exception) {
    return ResponseEntity.badRequest().body(exception.getMessage());
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  public ResponseEntity<String> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException exception) {
    return ResponseEntity.status(413).body("Il file supera il limite massimo consentito di 25 MB.");
  }
}
