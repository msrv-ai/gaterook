package ai.msrv.gaterook.core.approvals;

import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.platform.Caller;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The core tool {@code approval_status(handle, wait_seconds)} (report 01 §3.5): the state of a request this endpoint
 * and principal made, waiting up to 25 s for a decision. When it was approved, the gateway already ran the call and
 * the (masked) result is here; the agent must never repeat the original call. A handle of another endpoint or
 * principal is "Unknown handle", like one that doesn't exist. Not transactional: the long poll holds nothing.
 */
@Component
public class ApprovalStatusTool {

  /** Longest wait of one call (a TS SDK and Codex request times out at 60 s). */
  public static final int MAX_WAIT_SECONDS = 25;

  private final ApprovalService approvals;
  private final ApprovalAnswers answers;

  public ApprovalStatusTool(ApprovalService approvals, ApprovalAnswers answers) {
    this.approvals = approvals;
    this.answers = answers;
  }

  /** {@code arguments} were validated against the tool's input schema. */
  public ToolResult call(Caller caller, String endpointId, JsonNode arguments) {
    String handle = arguments.path("handle").asString("");
    int wait = Math.max(0, Math.min(MAX_WAIT_SECONDS, arguments.path("wait_seconds").asInt(0)));
    Optional<ApprovalView> found = approvals.forCaller(handle, endpointId, caller.principalId());
    if (found.isEmpty()) {
      return ApprovalAnswers.unknown(handle);
    }
    ApprovalView now = found.get();
    if (!now.terminal() && wait > 0) {
      now = approvals.await(handle, Duration.ofSeconds(wait));
    }
    return answers.state(now);
  }
}
