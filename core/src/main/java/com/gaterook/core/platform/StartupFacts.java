package com.gaterook.core.platform;

import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/**
 * Facts of this start that the ready line reports and other steps fill in: whether the gateway home was empty (the
 * app opens onboarding, {@code GET /api/onboarding} repeats it) and when the core started ({@code uptimeMs} of
 * {@code GET /api/health}). The locked mode is {@link MasterKeyLock}'s.
 */
public final class StartupFacts {

  private final Instant startedAt;
  private volatile boolean firstRun;
  private volatile Set<String> migrated = Set.of();

  public StartupFacts(Instant startedAt) {
    this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
  }

  public boolean firstRun() {
    return firstRun;
  }

  /** The database was created by this start. */
  public void firstRun(boolean value) {
    this.firstRun = value;
  }

  /** The Flyway versions this start applied ({@code "86"}). */
  public void migrated(Collection<String> versions) {
    this.migrated = Set.copyOf(versions);
  }

  /** Whether this start applied the migration {@code version}: the database was older until a moment ago. */
  public boolean migratedNow(String version) {
    return migrated.contains(version);
  }

  /** When the Spring context of this core began to start. */
  public Instant startedAt() {
    return startedAt;
  }
}
