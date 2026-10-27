package ai.msrv.gaterook.core.platform.internal.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Table {@code setting}; reached through {@code platform.SettingStore} only. */
public interface SettingRepository extends JpaRepository<SettingEntity, String> {}
