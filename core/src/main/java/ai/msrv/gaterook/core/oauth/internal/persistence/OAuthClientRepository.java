package ai.msrv.gaterook.core.oauth.internal.persistence;

import ai.msrv.gaterook.core.platform.Ingress;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code oauth_client}. */
public interface OAuthClientRepository extends JpaRepository<OAuthClientEntity, String> {

  Optional<OAuthClientEntity> findByClientId(String clientId);

  long countByIngressAndKind(Ingress ingress, OAuthClientKind kind);
}
