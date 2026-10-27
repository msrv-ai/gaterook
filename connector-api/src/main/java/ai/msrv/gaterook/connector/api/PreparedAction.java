package ai.msrv.gaterook.connector.api;

import tools.jackson.databind.JsonNode;

/**
 * Result of {@link Connector#prepare}.
 *
 * <ul>
 *   <li>{@code APPROVAL}: exact action + opaque state kept in the request and passed to execute() (k8s
 *       resourceVersion + sha256 of the dry-run);</li>
 *   <li>{@code REJECT}: error for the agent, no request (conflict, validation, paused);</li>
 *   <li>{@code NO_CHANGE}: "unchanged", no request.</li>
 * </ul>
 */
public record PreparedAction(Outcome outcome, ActionPreview preview, JsonNode preparedState, String message) {

  public enum Outcome { APPROVAL, REJECT, NO_CHANGE }

  public static PreparedAction approval(ActionPreview p, JsonNode state) {
    return new PreparedAction(Outcome.APPROVAL, p, state, null);
  }

  public static PreparedAction reject(String messageForAgent) {
    return new PreparedAction(Outcome.REJECT, null, null, messageForAgent);
  }

  public static PreparedAction noChange(String messageForAgent) {
    return new PreparedAction(Outcome.NO_CHANGE, null, null, messageForAgent);
  }
}
