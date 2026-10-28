package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.approvals.ApprovalAnswers;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * How the outcome of an approval reaches the agent (ARCHITECTURE.md A3, §5 step 11):
 * <ul>
 *   <li><b>MCP App</b> — a client advertising the MCP Apps HTML MIME type gets the handle immediately, so it can
 *       render an approval card. The person reviews and approves the exact action in GateRook;</li>
 *   <li><b>MRTR</b> — a modern client that declared {@code elicitation.url} in this request, on an endpoint with
 *       {@code approvalDelivery = auto}: {@code input_required} with a URL elicitation of {@code /approvals/<id>} and a
 *       sealed {@code requestState}; each retry is held up to 20 s; {@code decline} rejects the request (always
 *       safe), {@code cancel} hands over to the handle; after 45 s since the first round, or past round 5, the handle
 *       answer ({@code resultType: complete}) and {@code approval_status} take over;</li>
 *   <li><b>handle</b> — everyone else: the first answer waits up to {@code firstWait} (15 s) for a quick decision,
 *       then returns {@code status: pending} with the handle.</li>
 * </ul>
 * The URL is never pre-authenticated and decides nothing: the page only opens the request in the app. Waiting holds
 * no transaction.
 */
@Component
public class ApprovalDelivery {

  public static final Duration HOLD_PER_ROUND = Duration.ofSeconds(20);
  public static final Duration MRTR_BUDGET = Duration.ofSeconds(45);
  public static final int MAX_ROUNDS = 5;
  public static final String INPUT_KEY = "gateway_approval";

  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

  private final ApprovalService approvals;
  private final ApprovalAnswers answers;
  private final RequestStateCodec codec;
  private final Clock clock;

  public ApprovalDelivery(ApprovalService approvals, ApprovalAnswers answers, RequestStateCodec codec, Clock clock) {
    this.approvals = approvals;
    this.answers = answers;
    this.codec = codec;
    this.clock = clock;
  }

  /**
   * What one round answers: a complete tool result, or {@code input_required}; {@code view} is the request as the
   * answer saw it (a tool with an {@code outputSchema} answers from it in its own shape).
   */
  public record Answer(ToolResult result, ObjectNode inputRequired, ApprovalView view) {}

  /** Whether this request gets MRTR. */
  public static boolean mrtr(McpRequest r) {
    return !r.env().supportsUi() && r.env().elicitationUrl() && r.mrtrAllowed();
  }

  /** The first answer to the call that made (or reused) the request. */
  public Answer first(McpRequest r, ApprovalView a, String argumentsDigest) {
    // The host needs the handle before it can render a card. Do not hold the call or open a parallel URL flow.
    if (r.env().supportsUi()) {
      return new Answer(a.pending() ? answers.pending(a) : answers.state(a), null, a);
    }
    if (mrtr(r)) {
      Instant now = clock.instant();
      ObjectNode requests = JsonNodeFactory.instance.objectNode();
      ObjectNode elicitation = requests.putObject(INPUT_KEY);
      elicitation.put("method", "elicitation/create");
      ObjectNode params = elicitation.putObject("params");
      params.put("mode", "url");
      params.put("url", answers.approvalUrl(a.id()));
      params.put("message", message(a, answers.agentAction(a)));
      return new Answer(null, ResultWriter.inputRequired(requests, seal(r, a, argumentsDigest, 1, now)), a);
    }
    ApprovalView now = r.firstWaitSeconds() > 0 ? approvals.await(a.id(), Duration.ofSeconds(r.firstWaitSeconds()))
        : a;
    return new Answer(now.pending() ? answers.pending(now) : answers.state(now), null, now);
  }

  /**
   * A retry that carries a valid {@code requestState}. Empty when the request isn't this caller's any more (the
   * call then goes the ordinary way).
   */
  public Optional<Answer> resume(McpRequest r, RequestStateCodec.State state, JsonNode inputResponses,
      String argumentsDigest) {
    Optional<ApprovalView> found = approvals.forCaller(state.approvalId(), r.snapshot().id(), r.caller().principalId());
    if (found.isEmpty()) {
      return Optional.empty();
    }
    ApprovalView view = found.get();
    String action = inputResponses == null ? null : inputResponses.path(INPUT_KEY).path("action").asString(null);
    if ("decline".equals(action)) {
      ApprovalView declined = approvals.declineInClient(view.id());
      ApprovalView after = declined == null ? view : declined;
      return Optional.of(new Answer(answers.state(after), null, after));
    }
    if ("cancel".equals(action)) {
      return Optional.of(handle(view));
    }
    Duration left = MRTR_BUDGET.minus(Duration.between(state.t0(), clock.instant()));
    if (view.terminal() || state.round() >= MAX_ROUNDS || left.isNegative() || left.isZero()) {
      return Optional.of(handle(view));
    }
    ApprovalView now = approvals.await(view.id(), left.compareTo(HOLD_PER_ROUND) < 0 ? left : HOLD_PER_ROUND);
    if (now.terminal()) {
      return Optional.of(new Answer(answers.state(now), null, now));
    }
    return Optional.of(new Answer(null, ResultWriter.inputRequired(null, seal(r, now, argumentsDigest,
        state.round() + 1, state.t0())), now));
  }

  private Answer handle(ApprovalView view) {
    return new Answer(view.terminal() ? answers.state(view) : answers.pending(view), null, view);
  }

  private String seal(McpRequest r, ApprovalView a, String argumentsDigest, int round, Instant t0) {
    return codec.seal(a.id(), r.caller().principalId(), r.snapshot().id(), "tools/call", a.tool(), argumentsDigest,
        round, t0, a.expiresAt().plusSeconds(60));
  }

  /**
   * What the client shows next to the link: the action as the agent's own arguments made it (read only; what else the
   * approver sees stays in the app), and where to decide.
   */
  private static String message(ApprovalView a, ApprovalAnswers.AgentAction action) {
    String until = LocalTime.ofInstant(a.expiresAt(), ZoneId.systemDefault()).format(CLOCK);
    StringBuilder m = new StringBuilder("GateRook: approve ").append(a.tool()).append(" on ")
        .append(a.connectionName()).append(" (").append(a.env().id()).append(")? ").append(action.text())
        .append("  Request ").append(a.id()).append(", auto-deny at ").append(until).append('.');
    if ("required".equals(a.commentPolicy())) {
      m.append(" A comment is required to approve on ").append(a.env().id()).append('.');
    }
    return m.append(" Decide in the GateRook window.").toString();
  }
}
