package com.gaterook.core.settings;

import com.gaterook.core.platform.GatewayDatabase;
import com.gaterook.core.platform.GatewayPaths;
import com.gaterook.core.platform.SettingStore;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Beans of the settings module that need wiring beyond their constructor. */
@Configuration(proxyBeanMethods = false)
class SettingsConfiguration {

  /** Where the job keeps what the last attempt did ({@code GET /api/backup}); apart from the person's section. */
  static final String BACKUP_STATE = "backup.state";

  /**
   * @param zone          the time zone of 03:00; empty means this computer's (tests pin one)
   * @param catchUpDelay  how long after the start a missed weekly copy is made
   */
  @Bean
  DatabaseBackupJob databaseBackupJob(GatewayDatabase database, GatewayPaths paths, SettingsService settings,
      SettingStore store, Clock clock, @Value("${gateway.backup.zone:}") String zone,
      @Value("${gateway.backup.catch-up-delay:1m}") Duration catchUpDelay) {
    return new DatabaseBackupJob(database, paths.backups(),
        // Keep the persisted toggle name so existing enabled/disabled preferences survive the schedule change.
        () -> settings.value("backup", "daily").asBoolean(true),
        () -> store.find(BACKUP_STATE).map(SettingStore.Setting::value),
        state -> store.put(BACKUP_STATE, state, null), clock,
        zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone), catchUpDelay);
  }
}
