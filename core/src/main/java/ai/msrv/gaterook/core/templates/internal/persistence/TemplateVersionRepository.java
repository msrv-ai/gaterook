package ai.msrv.gaterook.core.templates.internal.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Table {@code template_version}: append-only, so this repository has no update or delete methods. */
public interface TemplateVersionRepository extends Repository<TemplateVersionEntity, String> {

  TemplateVersionEntity save(TemplateVersionEntity version);

  Optional<TemplateVersionEntity> findById(String id);

  Optional<TemplateVersionEntity> findByTemplateIdAndTemplateVersion(String templateId, int templateVersion);

  List<TemplateVersionEntity> findByTemplateIdOrderByTemplateVersionDesc(String templateId);
}
