package ai.msrv.gaterook.core.audit.internal.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

/** Table {@code setup_change}: inserts and reads only, like {@link ToolCallRepository}. */
public interface SetupChangeRepository extends Repository<SetupChangeEntity, Long> {

  SetupChangeEntity save(SetupChangeEntity change);

  Optional<SetupChangeEntity> findById(Long id);

  /** The head of the hash chain. */
  Optional<SetupChangeEntity> findTopByOrderByIdDesc();

  List<SetupChangeEntity> findAllByOrderByIdDesc(Pageable page);

  List<SetupChangeEntity> findByIdGreaterThanOrderByIdAsc(long afterId, Pageable page);

  long count();
}
