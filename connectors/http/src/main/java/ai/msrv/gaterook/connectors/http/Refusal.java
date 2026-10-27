package ai.msrv.gaterook.connectors.http;

import ai.msrv.gaterook.connector.api.Classification;
import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.PolicyView;

/**
 * A request the connector refuses before any I/O, with the rule it breaks ({@code url-not-allowed},
 * {@code header-not-allowed}, …; the policy prefixes it: {@code stage.http.url-not-allowed}) and a text for the agent.
 * {@code classify} turns it into a DENY, {@code execute} — which repeats the checks it can make without the policy —
 * into a refusal of the call.
 */
final class Refusal extends Exception {

  private static final long serialVersionUID = 1L;

  private final String rule;
  private final String hint;

  Refusal(String rule, String message, String hint) {
    super(message, null, false, false);
    this.rule = rule;
    this.hint = hint;
  }

  static Refusal of(String rule, String message) {
    return new Refusal(rule, message, null);
  }

  String rule() {
    return rule;
  }

  String hint() {
    return hint;
  }

  Classification deny(PolicyView policy) {
    return Classification.deny(policy.rule(rule), getMessage(), hint);
  }

  ConnectorException toException() {
    String text = hint == null ? getMessage() : getMessage() + " " + hint;
    return rule.equals("invalid-request") ? ConnectorException.badInput(text) : ConnectorException.denied(text);
  }
}
