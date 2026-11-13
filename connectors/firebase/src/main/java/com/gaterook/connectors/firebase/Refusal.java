package com.gaterook.connectors.firebase;

import com.gaterook.connector.api.ConnectorException;

/**
 * Why a call can't run, found without I/O: the same checks refuse it in {@code classify} (a policy denial with
 * {@link #rule}) and again in {@code execute}, where it becomes a {@link ConnectorException}.
 */
final class Refusal extends Exception {
  private static final long serialVersionUID = 1L;

  /** Calls outside what the connection allows; any other refusal is a bad argument. */
  static final String NOT_ALLOWED_COLLECTION = "collection-not-allowed";
  static final String NOT_ALLOWED_PATH = "path-not-allowed";
  static final String BAD_ARGUMENT = "bad-argument";
  static final String ROOT_WRITE = "root-write";
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
      case NOT_ALLOWED_COLLECTION, NOT_ALLOWED_PATH, ROOT_WRITE -> ConnectorException.denied(text);
      default -> ConnectorException.badInput(text);
    };
  }
}
