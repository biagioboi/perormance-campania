package it.unisa.performance.service;

import it.unisa.performance.domain.RagSource;
import it.unisa.performance.dto.RagSourceResponse;
import it.unisa.performance.repository.RagSourceRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RagSourceService {

  private final RagSourceRepository ragSourceRepository;
  private final QdrantClientService qdrantClientService;

  public RagSourceService(RagSourceRepository ragSourceRepository, QdrantClientService qdrantClientService) {
    this.ragSourceRepository = ragSourceRepository;
    this.qdrantClientService = qdrantClientService;
  }

  @Transactional(readOnly = true)
  public List<RagSourceResponse> list() {
    return ragSourceRepository.findAllByOrderByUploadedAtDesc().stream().map(this::toResponse).toList();
  }

  @Transactional
  public void delete(Long id) {
    var source = ragSourceRepository.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Fonte RAG non trovata: " + id));
    qdrantClientService.deleteBySource(source.getId());
    ragSourceRepository.delete(source);
  }

  private RagSourceResponse toResponse(RagSource source) {
    return new RagSourceResponse(
        source.getId(),
        source.getFileName(),
        source.getContentType(),
        source.getFileSizeBytes(),
        source.getStatus() == null ? null : source.getStatus().name(),
        source.getPurpose() == null ? null : source.getPurpose().name(),
        source.getStatusMessage(),
        source.getUploadedAt(),
        source.getIngestedAt(),
        source.getPageCount(),
        source.getChunkCount());
  }
}
