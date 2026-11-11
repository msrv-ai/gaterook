package ai.msrv.gaterook.connectors.cosmosdb;

import ai.msrv.gaterook.connector.api.ConnectorException;

/**
 * Why a call can't run, found without I/O: the same checks refuse it in {@code classify} (a policy denial with
 * {@link #rule}) and again in {@code execute}, where it becomes a {@link ConnectorException}.
 */
final class Refusal extends Exception {
  private static final long serialVersionUID = 1L;

  static final String BAD_ARGUMENT = "bad-argument";
  static final String DATABASE_NOT_ALLOWED = "database-not-allowed";
  static final String CONTAINER_NOT_ALLOWED = "container-not-allowed";
  static final String CROSS_PARTITION = "cross-partition";
  static final String READ_ONLY_KEY = "read-only-key";
  static final String TOO_LARGE = "too-large";

  final String rule;
  final String hint;

  Refusal(String rule, String message, String hint) {
    super(message, null, false, false);
    this.rule = rule;
    this.hint = hint;
  }

  static Refusal bad(String message, String hint) {
    return new Refusal(BAD_ARGUMENT, message, hint);
  }

  /** As {@code execute} reports it: what the connection doesn't allow is denied, the rest is a bad argument. */
  ConnectorException toException() {
    String text = hint == null ? getMessage() : getMessage() + " " + hint;
    return switch (rule) {
      case DATABASE_NOT_ALLOWED, CONTAINER_NOT_ALLOWED, CROSS_PARTITION, READ_ONLY_KEY, TOO_LARGE ->
          ConnectorException.denied(text);
      default -> ConnectorException.badInput(text);
    };
  }
}
