package ai.msrv.gaterook.core.platform;

import java.time.Instant;
import java.util.Objects;

/**
 * Facts of this start that the ready line reports and other steps fill in: whether the gateway home was empty (the
 * app opens onboarding, {@code GET /api/onboarding} repeats it) and when the core started ({@code uptimeMs} of
 * {@code GET /api/health}). The locked mode is {@link MasterKeyLock}'s.
 */
public final class StartupFacts {

  private final Instant startedAt;
  private volatile boolean firstRun;

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

  /** When the Spring context of this core began to start. */
  public Instant startedAt() {
    return startedAt;
  }
}
