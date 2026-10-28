package ai.msrv.gaterook.core.mcp.internal;

import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.approvals.ActionInspector;
import ai.msrv.gaterook.core.approvals.ApprovalAnswers;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.platform.EventBus;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Caller-scoped approval cards. MCP Apps visibility is presentation, never authorization: the same bearer caller
 * can invoke every interaction without an iframe. Interactions may inspect, open the protected app, or decline;
 * only the app's separately authenticated decision flow may approve. Card data is the existing agent-visible,
 * masked request projection, never a prepared action or a capability to decide it.
 */
@Component
public class ApprovalCards {

  static final int PREVIEW_LIMIT = 12_000;
  private static final int JSON_DEPTH_LIMIT = 64;
  static final int MAX_OPEN_CALLERS = 1_024;
  static final Duration OPEN_INTERVAL = Duration.ofSeconds(5);
  private static final Pattern HANDLE = Pattern.compile("ap_[a-z0-9]{12}");
  private static final Set<String> ACTIONS = Set.of("status", "open", "deny");
  private static final Set<String> RENDER_FIELDS = Set.of("handle");
  private static final Set<String> ACTION_FIELDS = Set.of("handle", "action");

  private final ApprovalService approvals;
  private final ApprovalAnswers answers;
  private final EventBus events;
  private final Clock clock;
  private final Map<String, Instant> lastOpen = new LinkedHashMap<>();

  public ApprovalCards(ApprovalService approvals, ApprovalAnswers answers, EventBus events, Clock clock) {
    this.approvals = approvals;
    this.answers = answers;
    this.events = events;
    this.clock = clock;
  }

  public ToolResult call(McpRequest r, ObjectNode args, boolean interaction) {
    Set<String> fields = interaction ? ACTION_FIELDS : RENDER_FIELDS;
    if (args == null || args.propertyNames().stream().anyMatch(name -> !fields.contains(name))) {
      return ToolResult.error("Invalid approval card arguments.");
    }
    JsonNode handleNode = args.get("handle");
    if (handleNode == null || !handleNode.isString() || !HANDLE.matcher(handleNode.asString()).matches()) {
      return ToolResult.error("Invalid approval handle.");
    }
    String handle = handleNode.asString();
    String action = interaction && args.path("action").isString() ? args.path("action").asString()
        : interaction ? null : "status";
    if (action == null || !ACTIONS.contains(action)) {
      return ToolResult.error("Approval cards can check status, open GateRook, or deny. Approve in GateRook.");
    }
    Optional<ApprovalView> found = approvals.forCaller(handle, r.snapshot().id(), r.caller().principalId());
    if (found.isEmpty()) {
      return ApprovalAnswers.unknown(handle);
    }
    ApprovalView view = found.get();
    if ("open".equals(action)) {
      if (!admitOpen(r.caller().principalId(), clock.instant())) {
        return ToolResult.error("GateRook was just opened. Wait a few seconds before opening it again.");
      }
      events.publish("ui.open", JsonNodeFactory.instance.objectNode().put("path", "/approvals/" + handle));
    } else if ("deny".equals(action) && canDeny(view, clock.instant())) {
      // Caller identity is established above. The client can only prevent an action; it never represents a
      // verified human decision and does not get an approval digest or a privileged decision channel.
      ApprovalView after = approvals.declineInClient(handle);
      if (after == null) {
        return ApprovalAnswers.unknown(handle);
      }
      view = after;
    }
    return snapshot(view, clock.instant());
  }

  private ToolResult snapshot(ApprovalView view, Instant now) {
    String status = view.status();
    ApprovalAnswers.AgentAction request = answers.agentAction(view);
    String language = "arguments".equals(request.kind()) ? "json" : request.kind();
    Preview preview = preview(request.text(), language);
    ObjectNode data = JsonNodeFactory.instance.objectNode();
    data.put("kind", "gaterook.approval-card");
    data.put("version", 1);
    data.put("handle", view.id());
    data.put("tool", view.tool());
    data.put("connection", view.connectionName());
    data.put("environment", view.env().id());
    data.put("status", status);
    data.put("expiresAt", view.expiresAt().toString());
    data.put("serverTime", now.toString());
    data.putObject("request").put("language", language).put("text", preview.text())
        .put("truncated", preview.truncated());
    data.put("commentRequired", "required".equals(view.commentPolicy()));
    data.put("canDeny", canDeny(view, now));
    data.put("canOpen", true);
    data.put("approvalInApp", true);
    String text = "GateRook request " + view.id() + ": " + status + ". "
        + ("pending".equals(status) ? "Not executed. Review the exact action and approve in GateRook."
            : "Open GateRook for the request details. Use approval_status to retrieve the execution result.");
    return ToolResult.json(data, text);
  }

  private static boolean canDeny(ApprovalView view, Instant now) {
    return view.pending() && now.isBefore(view.expiresAt());
  }

  /** Bound memory as well as focus-stealing frequency, including callers rotating among their own requests. */
  private synchronized boolean admitOpen(String principal, Instant now) {
    lastOpen.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().plus(OPEN_INTERVAL)));
    if (lastOpen.containsKey(principal) || lastOpen.size() >= MAX_OPEN_CALLERS) {
      return false;
    }
    lastOpen.put(principal, now);
    return true;
  }

  private record Preview(String text, boolean truncated) {}

  /** Inspect a bounded prefix, then cap expanded escapes without splitting a code point or escape marker. */
  private static Preview preview(String source, String language) {
    String input = prefix(source, PREVIEW_LIMIT);
    boolean truncated = input.length() < source.length();
    if (!truncated && "json".equals(language)) {
      input = prettyJson(input);
    }
    StringBuilder out = new StringBuilder();
    for (ActionInspector.Segment segment : ActionInspector.inspect(input, language).segments()) {
      // A compact card has no separate token markup. Show look-alikes as visible codes too, so an unboxed letter
      // cannot impersonate an identifier in the preview. The native app retains the full annotated action.
      String value = "confusable".equals(segment.t())
          ? segment.v().codePoints().mapToObj(cp -> String.format(java.util.Locale.ROOT, "‹U+%04X›", cp))
              .collect(java.util.stream.Collectors.joining())
          : segment.v();
      int room = PREVIEW_LIMIT - out.length();
      if (value.length() > room) {
        if ("text".equals(segment.t())) {
          out.append(prefix(value, room));
        }
        truncated = true;
        break;
      }
      out.append(value);
    }
    return new Preview(out.toString(), truncated);
  }

  /**
   * The masked arguments already come from CanonicalJson. Add whitespace only: parsing and serializing them
   * again could change a number or an escape. Keep compact JSON if indentation would exceed the preview budget,
   * or if an unexpectedly deep or noncanonical value reaches this display-only formatter.
   */
  private static String prettyJson(String source) {
    StringBuilder out = new StringBuilder(source.length());
    char[] containers = new char[JSON_DEPTH_LIMIT];
    int depth = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int i = 0; i < source.length(); i++) {
      char c = source.charAt(i);
      if (quoted) {
        out.append(c);
        if (escaped) {
          escaped = false;
        } else if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          quoted = false;
        }
      } else if (c == '"') {
        quoted = true;
        out.append(c);
      } else if (c == '{' || c == '[') {
        if (depth == JSON_DEPTH_LIMIT) return source;
        containers[depth++] = c;
        out.append(c);
        char close = c == '{' ? '}' : ']';
        if (i + 1 < source.length() && source.charAt(i + 1) != close) {
          indent(out, depth);
        }
      } else if (c == '}' || c == ']') {
        char open = c == '}' ? '{' : '[';
        if (depth == 0 || containers[--depth] != open) return source;
        if (i > 0 && source.charAt(i - 1) != open) indent(out, depth);
        out.append(c);
      } else if (c == ',') {
        out.append(c);
        indent(out, depth);
      } else if (c == ':') {
        out.append(": ");
      } else if (Character.isWhitespace(c)) {
        return source;
      } else {
        out.append(c);
      }
      if (out.length() > PREVIEW_LIMIT) return source;
    }
    return quoted || depth != 0 ? source : out.toString();
  }

  private static void indent(StringBuilder out, int depth) {
    out.append('\n').append("  ".repeat(depth));
  }

  private static String prefix(String value, int limit) {
    int end = Math.min(value.length(), limit);
    if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))
        && Character.isLowSurrogate(value.charAt(end))) {
      end--;
    }
    return value.substring(0, end);
  }
}
