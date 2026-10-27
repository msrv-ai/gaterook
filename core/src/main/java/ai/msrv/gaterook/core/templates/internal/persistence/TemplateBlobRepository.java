package ai.msrv.gaterook.core.templates.internal.persistence;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/** Table {@code template_blob}: content-addressed, written once. */
public interface TemplateBlobRepository extends Repository<TemplateBlobEntity, String> {

  TemplateBlobEntity save(TemplateBlobEntity blob);

  Optional<TemplateBlobEntity> findById(String sha256);

  boolean existsById(String sha256);
}
