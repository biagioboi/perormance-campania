package it.unisa.performance.repository;

import it.unisa.performance.domain.GenerationRun;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationRunRepository extends JpaRepository<GenerationRun, Long> {
  Optional<GenerationRun> findTopByOrderByCreatedAtDesc();

  @EntityGraph(attributePaths = "objectives")
  List<GenerationRun> findAllByOrderByCreatedAtDesc();

  @Override
  @EntityGraph(attributePaths = "objectives")
  Optional<GenerationRun> findById(Long id);
}
