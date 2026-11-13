package com.gaterook.connectors.s3;

import com.gaterook.connector.api.ConnectorException;

/**
 * A call the connector refuses before any request: {@code classify} turns it into a denial with its rule id
 * ({@code dev.s3.bucket-not-allowed}), {@code prepare} into a rejection and {@code execute} into an error.
 */
final class Refusal extends Exception {
  private static final long serialVersionUID = 1L;

  final String rule;
  final String hint;

  Refusal(String rule, String message, String hint) {
    super(message, null, false, false);
    this.rule = rule;
    this.hint = hint;
  }

  static Refusal badArgument(String message) {
    return new Refusal("bad-argument", message.endsWith(".") ? message : message + ".", null);
  }

  /** The message with its hint, for an agent that gets no rule id. */
  String forAgent() {
    return hint == null ? getMessage() : getMessage() + " " + hint;
  }

  ConnectorException toException() {
    return rule.equals("bad-argument") ? ConnectorException.badInput(forAgent())
        : ConnectorException.denied(forAgent());
  }
}
