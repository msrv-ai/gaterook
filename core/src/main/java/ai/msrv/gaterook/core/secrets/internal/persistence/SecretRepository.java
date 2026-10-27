package ai.msrv.gaterook.core.secrets.internal.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Table {@code secret}. */
public interface SecretRepository extends JpaRepository<SecretEntity, String> {

  Optional<SecretEntity> findByName(String name);

  List<SecretEntity> findByOwnerKindAndOwnerId(SecretOwnerKind ownerKind, String ownerId);

  Optional<SecretEntity> findByOwnerKindAndOwnerIdAndField(SecretOwnerKind ownerKind, String ownerId, String field);

  List<SecretEntity> findByStoreId(String storeId);

  long countByKind(SecretKind kind);

  /** Local secrets that hold a value: what the master key protects. */
  long countByKindAndCurrentVersionIsNotNull(SecretKind kind);

  List<SecretEntity> findByKindAndCurrentVersionIsNotNull(SecretKind kind);

  List<SecretEntity> findAllByOrderByNameAsc();

  /** A new master key in locked mode: every local secret needs a value again. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update versioned SecretEntity s set s.currentVersion = null where s.kind = :kind")
  int clearCurrentVersions(SecretKind kind);
}
