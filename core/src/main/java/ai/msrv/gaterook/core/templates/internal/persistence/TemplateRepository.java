package ai.msrv.gaterook.core.templates.internal.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code template}. */
public interface TemplateRepository extends JpaRepository<TemplateEntity, String> {

  List<TemplateEntity> findByConnectionId(String connectionId);

  List<TemplateEntity> findByKindOrderByNameAsc(TemplateType kind);

  List<TemplateEntity> findAllByOrderByNameAsc();

  Optional<TemplateEntity> findByConnectionIdAndKindAndName(String connectionId, TemplateType kind, String name);
}
