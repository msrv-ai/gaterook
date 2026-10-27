package ai.msrv.gaterook.core.secrets.internal.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code secret_lease}. */
public interface SecretLeaseRepository extends JpaRepository<SecretLeaseEntity, String> {

  List<SecretLeaseEntity> findByRevokedAtIsNull();

  List<SecretLeaseEntity> findBySecretIdAndRevokedAtIsNull(String secretId);
}
