package ai.msrv.gaterook.core.platform.db;

import ai.msrv.gaterook.core.platform.BeforeMigrations;
import ai.msrv.gaterook.core.platform.GatewayPaths;
import ai.msrv.gaterook.core.platform.boot.GatewayStartupException;
import ai.msrv.gaterook.core.platform.StartupFacts;
import ai.msrv.gaterook.core.platform.boot.StartupProtocol;
import ai.msrv.gaterook.core.platform.boot.StartupReason;
import ai.msrv.gaterook.core.platform.boot.StartupStep;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfoService;
import org.flywaydb.core.api.MigrationVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;

/**
 * Runs Flyway at start (ARCHITECTURE.md §4): reports the {@code db-migrations} step, and before any change to an
 * existing schema takes {@code VACUUM INTO backups/pre-migration-<version>.db} (0600, never rotated by the daily
 * backup), so a failed or unwanted upgrade can be undone.
 */
final class GatewayMigrationStrategy implements FlywayMigrationStrategy {

  private static final Logger log = LoggerFactory.getLogger(GatewayMigrationStrategy.class);

  private final GatewayPaths paths;
  private final StartupFacts facts;
  private final List<BeforeMigrations> before;
  private final ParallelSchemaValidation schemaValidation;

  GatewayMigrationStrategy(GatewayPaths paths, StartupFacts facts, List<BeforeMigrations> before,
      ParallelSchemaValidation schemaValidation) {
    this.paths = paths;
    this.facts = facts;
    this.before = List.copyOf(before);
    this.schemaValidation = schemaValidation;
  }

  @Override
  public void migrate(Flyway flyway) {
    try {
      migrateSchema(flyway);
    } catch (RuntimeException | Error e) {
      schemaValidation.migrationsFailed(e);
      throw e;
    }
    // The mapping check, beside the persistence unit's build, waits for this.
    schemaValidation.migrated();
  }

  private void migrateSchema(Flyway flyway) {
    // Steps that need no database come first: the status screen lists "Master key" before "Database migrations".
    for (BeforeMigrations step : before) {
      StartupStep id = StartupStep.byId(step.step());
      StartupProtocol.current().running(id);
      step.run();
      StartupProtocol.current().done(id);
    }
    StartupProtocol.current().running(StartupStep.DB_MIGRATIONS);
    MigrationInfoService info = flyway.info();
    boolean fresh = info.applied().length == 0;
    facts.firstRun(fresh && !paths.databaseExisted());
    if (!fresh && info.pending().length > 0) {
      backUp(flyway, info.current() == null ? null : info.current().getVersion());
    }
    try {
      int applied = flyway.migrate().migrationsExecuted;
      if (applied > 0) {
        log.info("Gateway database migrated: {} migration(s) applied, schema version {}", applied,
            flyway.info().current().getVersion());
      }
    } catch (FlywayException e) {
      throw new GatewayStartupException(StartupReason.DB_MIGRATION_FAILED,
          "Database migration failed: " + e.getMessage(), e);
    }
    // "done" is reported by DatabaseSelfCheck, once the migrated schema also proved usable.
  }

  private void backUp(Flyway flyway, MigrationVersion current) {
    String version = current == null ? "unknown" : current.getVersion();
    Path target = paths.backups().resolve("pre-migration-" + version + ".db");
    if (Files.exists(target)) {
      target = paths.backups().resolve("pre-migration-" + version + "-" + System.currentTimeMillis() + ".db");
    }
    try {
      // VACUUM INTO accepts an existing empty file: create it owner-only first, so the copy is never readable by
      // others, not even for a moment.
      Files.createFile(target);
      GatewayPaths.ownerOnlyFile(target);
      try (Connection connection = flyway.getConfiguration().getDataSource().getConnection();
          Statement statement = connection.createStatement()) {
        statement.execute("VACUUM INTO '" + target.toAbsolutePath().toString().replace("'", "''") + "'");
      }
      log.info("Gateway database backed up before migration to {}", target);
    } catch (IOException | SQLException e) {
      throw new GatewayStartupException(StartupReason.DB_MIGRATION_FAILED,
          "Can't back up the database before migrating it: " + e.getMessage(), e);
    }
  }
}
