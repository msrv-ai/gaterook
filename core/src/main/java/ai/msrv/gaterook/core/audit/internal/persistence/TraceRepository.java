package ai.msrv.gaterook.core.audit.internal.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code trace}. */
public interface TraceRepository extends JpaRepository<TraceEntity, String> {

  List<TraceEntity> findByLastAtBeforeOrderByLastAtDesc(Instant before, Pageable page);
}
