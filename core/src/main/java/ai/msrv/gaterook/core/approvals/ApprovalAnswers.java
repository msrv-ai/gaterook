package ai.msrv.gaterook.core.approvals;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.LocalListener;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.policy.PolicyService;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the agent is told about a request (ARCHITECTURE.md §5.1, §5 step 11, report 01 §3.2/§3.5), as neutral results
 * the MCP layer writes in the call's revision. Only the tool, connection, environment and the action as the agent's
 * own arguments made it reach the agent ({@link #agentAction}): impact, fields, diff, warnings and every text a
 * connector built in {@code prepare} are the approver's. The texts say first what happened ("NOT EXECUTED YET"), then
 * what to do, because a model reads {@code isError:false} as success.
 */
@Component
public class ApprovalAnswers {

  private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

  /** Kinds whose exact text is the agent's own input: the statement, the command, the request it sent. */
  private static final Set<ActionPreview.Kind> OWN_TEXT = Set.of(ActionPreview.Kind.SQL, ActionPreview.Kind.COMMAND,
      ActionPreview.Kind.HTTP);

  /**
   * The action as the agent may see it.
   *
   * @param kind {@code sql}, {@code sh}, {@code http} (its own text), or {@code arguments} (its own arguments, when the
   *             exact action holds more than they do: a diff against live state, a runbook's command, a template's
   *             statement, a plugin's or an MCP server's description)
   */
  public record AgentAction(String kind, String text) {

    public boolean ownText() {
      return !"arguments".equals(kind);
    }
  }

  private final LocalListener listener;
  private final Masker masker;
  private final PolicyService policies;

  public ApprovalAnswers(LocalListener listener, Masker masker, PolicyService policies) {
    this.listener = listener;
    this.masker = masker;
    this.policies = policies;
  }

  /**
   * What the agent may see of the action (§5 step 11): the exact text only when it is what the agent itself sent — SQL,
   * a command or an HTTP request of a built-in tool — and otherwise its own arguments in canonical JSON; either way
   * through the masker with the environment's policy (§9: the masker runs before anything is serialized for the
   * agent). A diff, a runbook's command line, a template's statement or a plugin's text stays with the approver.
   */
  public AgentAction agentAction(ApprovalView a) {
    MaskPolicy mask = policies.maskPolicy(a.env());
    // Built-in tools only: a template's, a plugin's or an MCP server's tool key names its source ("tpl:…", "mcp:…").
    if (OWN_TEXT.contains(a.action().kind()) && a.toolKey() != null && a.toolKey().indexOf(':') < 0) {
      return new AgentAction(language(a), masker.text(a.action().text(), new Taint(), mask).text());
    }
    JsonNode arguments = a.call() == null ? null : a.call().path("arguments");
    JsonNode own = arguments != null && arguments.isObject() ? arguments : JsonNodeFactory.instance.objectNode();
    return new AgentAction("arguments", CanonicalJson.text(masker.tree(own, new Taint(), mask).tree()));
  }

  /** {@code http://127.0.0.1:7465/approvals/ap_…}: a page that only opens the request in the app. */
  public String approvalUrl(String id) {
    return listener.origin() + "/approvals/" + id;
  }

  /** The handle answer: not executed yet, poll {@code approval_status}. */
  public ToolResult pending(ApprovalView a) {
    AgentAction action = agentAction(a);
    StringBuilder text = new StringBuilder("NOT EXECUTED YET. A person must approve this exact action in MCP Gateway.")
        .append("\nHandle: ").append(a.id())
        .append("\nAction (").append(action.kind()).append(" on ").append(a.connectionName()).append(", ")
        .append(a.env().id()).append("):\n").append(action.text());
    if (!action.ownText()) {
      text.append("\nIn MCP Gateway the person sees the exact action these arguments make.");
    }
    text.append("\nApproval page: ").append(approvalUrl(a.id()))
        .append("\nAuto-deny at ").append(time(a.expiresAt())).append('.');
    if ("required".equals(a.commentPolicy())) {
      text.append("\nA comment is required to approve on ").append(a.env().id()).append('.');
    }
    text.append("\nTell the user what you asked for and why, then call approval_status with this handle. Don't repeat ")
        .append(a.tool()).append('.');
    ObjectNode s = base(a, "pending");
    s.put("pollAfterMs", 0);
    return ToolResult.json(s, text.toString());
  }

  /** The state of a request for {@code approval_status} or a held call: pending, running, or how it ended. */
  public ToolResult state(ApprovalView a) {
    return switch (a.status()) {
      case "pending" -> {
        ObjectNode s = base(a, "pending");
        yield ToolResult.json(s, "Still waiting for a decision in MCP Gateway (auto-deny at " + time(a.expiresAt())
            + "). Call approval_status again with handle " + a.id() + ".");
      }
      case "running" -> ToolResult.json(base(a, "running"), "Approved by you" + via(a) + "; " + a.tool()
          + " is running on " + a.connectionName() + " (" + a.env().id() + ") now. Call approval_status again.");
      case "completed" -> completed(a);
      case "rejected" -> refused(a);
      case "expired" -> error(base(a, "expired"), "No decision before " + time(a.expiresAt()) + ": the request "
          + "expired and nothing ran. Ask the user before trying again.");
      case "failed" -> failed(a);
      default -> error(base(a, a.status()), "The request was cancelled. Nothing ran.");
    };
  }

  /**
   * The answer to the call itself when its tool publishes an {@code outputSchema} — a READ tool whose call still went
   * to a person, like {@code sql_query} with a function that isn't built in (§9 "SQL reads cannot write"). The spec
   * wants every result that isn't an error to match that schema, and clients (the TS SDK) refuse one that doesn't. A
   * call completed by the time it answers (first wait, an MRTR round) answers with the executed result's own
   * {@code structuredContent}, which matches, and names the request in {@code _meta["ai.msrv.gaterook/approval"]};
   * every other state (pending, running, rejected, expired, failed, a result no longer stored) keeps its text and
   * handle but is {@code isError}, which no client validates against the schema.
   */
  public ToolResult typed(ApprovalView a, ToolResult answer) {
    ToolResult executed = a == null ? null : a.result();
    if (a != null && "completed".equals(a.status()) && executed != null && !executed.isError()
        && executed.structuredContent() != null) {
      ObjectNode meta = JsonNodeFactory.instance.objectNode();
      meta.putObject("ai.msrv.gaterook/approval").put("handle", a.id()).put("status", "completed");
      return new ToolResult(completed(a).content(), executed.structuredContent(), false, null, executed.truncated(),
          executed.truncatedReason(), List.of(), executed.maskedCount(), executed.resultBytes(), meta);
    }
    return asError(answer);
  }

  /** The same answer as {@code isError}: what isn't the tool's own output can't claim its schema. */
  public static ToolResult asError(ToolResult r) {
    return new ToolResult(r.content(), r.structuredContent(), true, r.table(), r.truncated(), r.truncatedReason(),
        r.maskPaths(), r.maskedCount(), r.resultBytes(), r.meta());
  }

  /** {@code isError} for a handle this caller doesn't own or that doesn't exist: the same answer for both. */
  public static ToolResult unknown(String handle) {
    return ToolResult.error("Unknown handle " + handle + " on this endpoint. Use the handle a call of this endpoint "
        + "returned.");
  }

  private ToolResult completed(ApprovalView a) {
    ObjectNode s = base(a, "completed");
    String head = "Approved by you" + via(a) + " and executed on " + a.connectionName() + " (" + a.env().id() + ")";
    if (a.result() == null) {
      s.put("resultExpired", true);
      return ToolResult.json(s, head + ". The result is no longer stored (it is kept 60 minutes); see Activity in MCP "
          + "Gateway.");
    }
    if (a.result().structuredContent() != null) {
      s.set("result", a.result().structuredContent());
    }
    List<ToolResult.Content> content = new ArrayList<>();
    List<ToolResult.Content> rest = new ArrayList<>(a.result().content());
    String first = rest.isEmpty() || rest.get(0).text() == null ? null : rest.remove(0).text();
    if (first == null) {
      content.add(ToolResult.Content.text(head + "."));
    } else if (first.length() <= 200 && first.indexOf('\n') < 0) {
      content.add(ToolResult.Content.text(head + ": " + first));
    } else {
      content.add(ToolResult.Content.text(head + ". Result:\n" + first));
    }
    content.addAll(rest);
    ToolResult r = new ToolResult(List.copyOf(content), s, false, null, a.result().truncated(),
        a.result().truncatedReason(), List.of(), a.result().maskedCount(), a.result().resultBytes(), null);
    return r;
  }

  private ToolResult refused(ApprovalView a) {
    ObjectNode s = base(a, "rejected");
    String text;
    if ("client".equals(a.decidedVia()) || "core".equals(a.decidedVia()) || a.error() != null) {
      text = (a.error() == null ? "Rejected. Not executed." : a.error()) + " Ask the user before trying again.";
      if (a.error() != null) {
        s.put("error", a.error());
      }
    } else if (a.comment() != null) {
      s.put("comment", a.comment());
      text = "Rejected by you" + via(a) + ": \"" + a.comment() + "\". Not executed. Ask the user before trying again.";
    } else {
      text = "Rejected by you" + via(a) + ". Not executed. Ask the user before trying again.";
    }
    return error(s, text);
  }

  private ToolResult failed(ApprovalView a) {
    ObjectNode s = base(a, "failed");
    String error = a.error() == null ? "the call failed" : a.error();
    s.put("error", error);
    return error(s, "Approved by you" + via(a) + ", but " + a.tool() + " failed on " + a.connectionName() + " ("
        + a.env().id() + "): " + error + " Don't repeat it without asking the user: it may have applied in part.");
  }

  private ObjectNode base(ApprovalView a, String status) {
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    s.put("status", status);
    s.put("handle", a.id());
    s.put("tool", a.tool());
    s.put("connection", a.connectionName());
    s.put("env", a.env().id());
    AgentAction agent = agentAction(a);
    ObjectNode action = s.putObject("action");
    action.put("kind", agent.kind());
    action.put("text", agent.text());
    s.put("approvalUrl", approvalUrl(a.id()));
    s.put("expiresAt", time(a.expiresAt()));
    if (a.decidedAt() != null) {
      s.put("decidedAt", time(a.decidedAt()));
    }
    return s;
  }

  private static ToolResult error(ObjectNode structured, String text) {
    return new ToolResult(List.of(ToolResult.Content.text(text)), structured, true, null, false, null, List.of(), 0, 0,
        null);
  }

  private static String via(ApprovalView a) {
    return switch (a.decidedVia() == null ? "" : a.decidedVia()) {
      case "slack" -> " from Slack";
      case "telegram" -> " from Telegram";
      case "desktop-notification" -> " from a notification";
      default -> "";
    };
  }

  private static String language(ApprovalView a) {
    return a.action().language() != null && !a.action().language().isBlank() ? a.action().language()
        : a.action().kind().name().toLowerCase(Locale.ROOT);
  }

  /** Local time with its offset, to the second: what the person's clock says. */
  static String time(Instant at) {
    return OffsetDateTime.ofInstant(at.truncatedTo(ChronoUnit.SECONDS), ZoneId.systemDefault()).format(ISO);
  }
}
