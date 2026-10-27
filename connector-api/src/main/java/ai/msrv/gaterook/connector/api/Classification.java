package ai.msrv.gaterook.connector.api;

import tools.jackson.databind.JsonNode;

/**
 * Result of {@link Connector#classify}. DENY carries the rule id shown in Activity ("prod.sql.block_ddl") and a
 * text for the agent; facts are non-secret.
 */
public record Classification(Kind kind, String ruleId, String reason, String hint, JsonNode facts) {

  public enum Kind { READ, MUTATION, DENY }

  public static Classification read() {
    return new Classification(Kind.READ, null, null, null, null);
  }

  public static Classification mutation(String reason) {
    return new Classification(Kind.MUTATION, null, reason, null, null);
  }

  public static Classification mutation(String reason, JsonNode facts) {
    return new Classification(Kind.MUTATION, null, reason, null, facts);
  }

  public static Classification deny(String ruleId, String reason, String hint) {
    return new Classification(Kind.DENY, ruleId, reason, hint, null);
  }
}
