package ai.msrv.gaterook.core.policy;

import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.PolicyView;
import java.util.Objects;

/**
 * What the policy says about one classified call (ARCHITECTURE.md §5 step 7).
 *
 * @param ruleId       the rule shown in Activity and to the agent ({@code prod.sql.block_ddl},
 *                     {@code dev.sql.write-approval}); {@code null} for {@link Outcome#ALLOW} and
 *                     {@link Outcome#UNKNOWN_TOOL}
 * @param reason       for DENY: the text for the agent; for APPROVAL: why it needs a person
 * @param view         the merged policy section the connector saw in {@code classify}
 * @param limits       effective limits of the call: min(environment, connection, endpoint, pair)
 * @param ratePerToken calls per minute of one principal on this environment
 * @param ratePerTool  calls per minute of one principal × tool
 */
public record Decision(Outcome outcome, String ruleId, String reason, String hint, PolicyView view, Limits limits,
    int ratePerToken, int ratePerTool) {

  /** The outcomes; there is no "write without approval" anywhere. */
  public enum Outcome {
    /** A READ on a Read only pair: runs now. */
    ALLOW,
    /** A mutation, or anything on an Approval pair: becomes an approval request. */
    APPROVAL,
    /** Refused, with {@code ruleId}. */
    DENY,
    /** The pair is Off: the agent is told the tool doesn't exist. */
    UNKNOWN_TOOL
  }

  public Decision {
    Objects.requireNonNull(outcome, "outcome");
  }
}
