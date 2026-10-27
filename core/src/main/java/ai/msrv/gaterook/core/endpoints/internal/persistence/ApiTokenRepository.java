package ai.msrv.gaterook.core.endpoints.internal.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Table {@code api_token}. */
public interface ApiTokenRepository extends JpaRepository<ApiTokenEntity, String> {

  /** The lookup of every authenticated MCP request. */
  Optional<ApiTokenEntity> findByHash(String hash);

  List<ApiTokenEntity> findByEndpointIdAndKindOrderByCreatedAtDesc(String endpointId, TokenKind kind);

  List<ApiTokenEntity> findByGrantId(String grantId);

  long countByGrantIdAndRevokedAtIsNullAndExpiresAtAfter(String grantId, Instant now);

  /** Bridge tokens are short-lived and not audit: rows past their expiry plus a day are purged. */
  @Modifying(clearAutomatically = true)
  @Query("delete from ApiTokenEntity t where t.kind = :kind and t.expiresAt < :before")
  int deleteExpired(TokenKind kind, Instant before);
}
