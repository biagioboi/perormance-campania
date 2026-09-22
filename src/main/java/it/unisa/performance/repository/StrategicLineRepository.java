package it.unisa.performance.repository;

import it.unisa.performance.domain.StrategicLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StrategicLineRepository extends JpaRepository<StrategicLine, Long> {
  Optional<StrategicLine> findByCode(String code);

  boolean existsByParentId(Long parentId);

  List<StrategicLine> findAllByOrderByCodeAsc();
}
