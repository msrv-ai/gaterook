package ai.msrv.gaterook.core.secrets.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code master_key_state}; one row with id {@value MasterKeyStateEntity#ID}. */
public interface MasterKeyStateRepository extends JpaRepository<MasterKeyStateEntity, Integer> {}
