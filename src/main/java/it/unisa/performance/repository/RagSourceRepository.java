package it.unisa.performance.repository;

import it.unisa.performance.domain.RagSource;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RagSourceRepository extends JpaRepository<RagSource, Long> {
  List<RagSource> findAllByOrderByUploadedAtDesc();
}
