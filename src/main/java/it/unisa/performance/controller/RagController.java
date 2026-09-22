package it.unisa.performance.controller;

import it.unisa.performance.domain.RagSourcePurpose;
import it.unisa.performance.dto.RagImportJobResponse;
import it.unisa.performance.dto.RagSourceResponse;
import it.unisa.performance.service.RagImportJobService;
import it.unisa.performance.service.RagSourceService;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/rag")
public class RagController {

  private final RagImportJobService ragImportJobService;
  private final RagSourceService ragSourceService;

  public RagController(RagImportJobService ragImportJobService, RagSourceService ragSourceService) {
    this.ragImportJobService = ragImportJobService;
    this.ragSourceService = ragSourceService;
  }

  @GetMapping("/sources")
  public List<RagSourceResponse> sources() {
    return ragSourceService.list();
  }

  @PostMapping(value = "/sources/import", consumes = "multipart/form-data")
  public RagImportJobResponse importSource(
      @RequestParam("data") MultipartFile file,
      @RequestParam(value = "purpose", defaultValue = "CONTENT") String purpose) throws IOException {
    return ragImportJobService.start(file, parsePurpose(purpose));
  }

  private RagSourcePurpose parsePurpose(String purpose) {
    try {
      return RagSourcePurpose.valueOf(purpose.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new IllegalArgumentException(
          "Tipo fonte non valido: " + purpose + ". Valori ammessi: CONTENT, GUIDELINE.");
    }
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<String> handleBadRequest(IllegalArgumentException exception) {
    return ResponseEntity.badRequest().body(exception.getMessage());
  }

  @GetMapping("/sources/import/{jobId}")
  public RagImportJobResponse importStatus(@PathVariable String jobId) {
    return ragImportJobService.status(jobId);
  }

  @DeleteMapping("/sources/{id}")
  public ResponseEntity<Void> deleteSource(@PathVariable Long id) {
    ragSourceService.delete(id);
    return ResponseEntity.noContent().build();
  }
}
