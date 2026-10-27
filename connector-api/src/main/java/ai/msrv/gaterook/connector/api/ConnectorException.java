package ai.msrv.gaterook.connector.api;

/**
 * A failed call. The agent gets {@link #getMessage()} as {@code isError} after masking.
 *
 * <p>Only {@code RESOURCE_UNAVAILABLE} and {@code TIMEOUT} count for the circuit breaker. {@code OUTCOME_UNKNOWN}:
 * the request may have been applied (dropped during COMMIT, after the POST); it is never retried.
 */
public class ConnectorException extends Exception {
  private static final long serialVersionUID = 1L;

  public enum Kind { RESOURCE_UNAVAILABLE, TIMEOUT, BAD_INPUT, RESOURCE_ERROR, DENIED, OUTCOME_UNKNOWN, INTERNAL }

  private final Kind kind;

  public ConnectorException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  public boolean countsForCircuitBreaker() {
    return kind == Kind.RESOURCE_UNAVAILABLE || kind == Kind.TIMEOUT;
  }

  public static ConnectorException unavailable(String m, Throwable c) {
    return new ConnectorException(Kind.RESOURCE_UNAVAILABLE, m, c);
  }

  public static ConnectorException timeout(String m) {
    return new ConnectorException(Kind.TIMEOUT, m, null);
  }

  public static ConnectorException badInput(String m) {
    return new ConnectorException(Kind.BAD_INPUT, m, null);
  }

  public static ConnectorException resourceError(String m, Throwable c) {
    return new ConnectorException(Kind.RESOURCE_ERROR, m, c);
  }

  public static ConnectorException denied(String m) {
    return new ConnectorException(Kind.DENIED, m, null);
  }

  public static ConnectorException outcomeUnknown(String m, Throwable c) {
    return new ConnectorException(Kind.OUTCOME_UNKNOWN, m, c);
  }
}
