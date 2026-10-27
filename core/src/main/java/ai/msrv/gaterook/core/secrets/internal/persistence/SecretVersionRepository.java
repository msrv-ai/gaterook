package ai.msrv.gaterook.core.secrets.internal.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Table {@code secret_version}. */
public interface SecretVersionRepository extends JpaRepository<SecretVersionEntity, String> {

  List<SecretVersionEntity> findBySecretIdOrderBySecretVersionDesc(String secretId);

  Optional<SecretVersionEntity> findBySecretIdAndSecretVersion(String secretId, int secretVersion);

  Optional<SecretVersionEntity> findFirstBySecretIdAndState(String secretId, SecretVersionState state);

  /** Distinct master key ids still needed to decrypt something: Restore checks the keyset covers all of them. */
  @Query("select distinct v.keyId from SecretVersionEntity v where v.wrappedDek is not null")
  List<Long> findKeyIdsInUse();

  /** Re-wrap progress of a rotation: data keys not yet wrapped by the target key. */
  @Query("select count(v) from SecretVersionEntity v where v.wrappedDek is not null and v.keyId <> :keyId")
  long countWrappedByOtherKeys(long keyId);

  /** The next batch of a rotation: data keys still wrapped by another key than {@code keyId}. */
  @Query("select v from SecretVersionEntity v where v.wrappedDek is not null and v.keyId <> :keyId order by v.id")
  List<SecretVersionEntity> findWrappedByOtherKeys(long keyId, Pageable page);

  /** Previous versions of secrets replaced before {@code cutoff}: the 7 days to roll back are over. */
  @Query("select v from SecretVersionEntity v where v.state = :state and v.secret.rotatedAt < :cutoff")
  List<SecretVersionEntity> findByStateAndReplacedBefore(SecretVersionState state, Instant cutoff);

  /** Crypto-shredding of everything the lost master key protected (Create a new master key). */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update SecretVersionEntity v set v.state = :destroyed, v.ciphertext = null, v.wrappedDek = null, "
      + "v.keyId = null, v.destroyedAt = :now where v.wrappedDek is not null")
  int destroyAllWrapped(SecretVersionState destroyed, Instant now);
}
