package it.unisa.performance.repository;

import it.unisa.performance.domain.Objective;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObjectiveRepository extends JpaRepository<Objective, Long> {
  List<Objective> findByGenerationRunIdOrderByIdAsc(Long generationRunId);

  Optional<Objective> findByGenerationRunIdAndPublicId(Long generationRunId, String publicId);
}
