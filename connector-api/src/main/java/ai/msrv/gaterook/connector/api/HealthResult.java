package ai.msrv.gaterook.connector.api;

/** Periodic health of a connection: "Healthy · 42 ms", "Slow · 2.4 s", "Unreachable". */
public record HealthResult(State state, long latencyMs, String message) {

  public enum State { HEALTHY, DEGRADED, UNREACHABLE }

  /** Answers slower than a second count as degraded. */
  public static HealthResult healthy(long ms) {
    return new HealthResult(ms > 1_000 ? State.DEGRADED : State.HEALTHY, ms, null);
  }

  public static HealthResult unreachable(String message) {
    return new HealthResult(State.UNREACHABLE, 0, message);
  }
}
