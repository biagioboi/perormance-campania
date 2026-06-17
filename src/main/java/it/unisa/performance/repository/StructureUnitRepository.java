package it.unisa.performance.repository;

import it.unisa.performance.domain.StructureUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StructureUnitRepository extends JpaRepository<StructureUnit, Long> {
  Optional<StructureUnit> findByCode(String code);

  List<StructureUnit> findAllByOrderByCodeAsc();
}
