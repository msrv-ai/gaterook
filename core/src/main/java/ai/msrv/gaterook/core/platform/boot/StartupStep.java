package ai.msrv.gaterook.core.platform.boot;

/** The start-up steps the status screen shows, in order (ARCHITECTURE.md §8.4, core-starting). */
public enum StartupStep {
  MASTER_KEY("master-key"),
  DB_MIGRATIONS("db-migrations"),
  LOCAL_API("local-api");

  private final String id;

  StartupStep(String id) {
    this.id = id;
  }

  /** The {@code step} of a progress line. */
  public String id() {
    return id;
  }

  /** The step whose {@link #id()} is {@code id}. */
  public static StartupStep byId(String id) {
    for (StartupStep step : values()) {
      if (step.id.equals(id)) {
        return step;
      }
    }
    throw new IllegalArgumentException("Unknown start-up step " + id);
  }
}
