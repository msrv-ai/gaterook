package ai.msrv.gaterook.core.oauth.internal.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code oauth_grant}. */
public interface OAuthGrantRepository extends JpaRepository<OAuthGrantEntity, String> {

  List<OAuthGrantEntity> findByEndpointId(String endpointId);

  List<OAuthGrantEntity> findByStateOrderByCreatedAtDesc(OAuthGrantState state);

  List<OAuthGrantEntity> findByClient_IdAndEndpointIdAndState(String oauthClientId, String endpointId,
      OAuthGrantState state);
}
