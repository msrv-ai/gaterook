package ai.msrv.gaterook.core.platform;

import ai.msrv.gaterook.connector.api.Env;
import java.util.Locale;
import java.util.Objects;
import tools.jackson.databind.JsonNode;

/**
 * Port: appends one entry to Activity › Setup changes (table {@code setup_change}). Implemented by the {@code audit}
 * module, which owns the append-only table and its hash chain; declared here so that every module can record its
 * changes without depending on {@code audit}.
 *
 * <p>Call it inside the transaction of the change itself: if the change rolls back, so does its record, and if the
 * record can't be written the change must not happen (fail-closed, ARCHITECTURE.md §9). The actor is always the
 * local user ("You", decision 1), filled in by the implementation.
 */
public interface SetupChangeRecorder {

  /**
   * Appends {@code change} in the caller's transaction.
   *
   * @throws IllegalStateException when the record can't be written; the caller's transaction must roll back
   */
  void record(SetupChange change);

  /**
   * One setup change as shown in the table "Time | What | Change | By | Reason".
   *
   * @param subject        what changed, e.g. {@code "ssh_exec on dev-vm, endpoint dev"}
   * @param from           the previous state for the Change column ({@code "Approval"}), or {@code null}
   * @param to             the new state ({@code "Off"}), or {@code null}
   * @param reason         the person's reason (≤ 500 chars in the API), or {@code null}
   * @param env            the environment concerned, or {@code null}
   * @param endpointSlug   the endpoint concerned, or {@code null}
   * @param connectionName the connection concerned, or {@code null}
   * @param details        non-secret structured details, or {@code null}
   */
  record SetupChange(What what, String subject, String from, String to, String reason, Env env, String endpointSlug,
      String connectionName, JsonNode details) {

    public SetupChange {
      Objects.requireNonNull(what, "what");
      if (subject == null || subject.isBlank()) {
        throw new IllegalArgumentException("A setup change needs a subject");
      }
    }
  }

  /** Column {@code setup_change.what}. */
  enum What {
    TUNNEL, MODE, TOKEN, GRANT, RISK_WARNING, PLUGIN, MCP_SERVER, CONNECTION, SECRET, POLICY, SETTINGS, CLIENT, OAUTH,
    HOST_KEY;

    /** The stored id: {@code risk-warning}, {@code host-key}, … */
    public String id() {
      return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
  }
}
