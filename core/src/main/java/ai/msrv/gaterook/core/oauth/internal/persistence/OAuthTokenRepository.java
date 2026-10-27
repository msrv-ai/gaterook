package ai.msrv.gaterook.core.oauth.internal.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code oauth_token}, keyed by token hash. */
public interface OAuthTokenRepository extends JpaRepository<OAuthTokenEntity, String> {

  List<OAuthTokenEntity> findByGrantId(String grantId);
}
