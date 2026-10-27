package ai.msrv.gaterook.core.notifications.internal;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.Env;
import ai.msrv.gaterook.core.approvals.ActionInspector;
import ai.msrv.gaterook.core.approvals.ApprovalService;
import ai.msrv.gaterook.core.approvals.ApprovalView;
import ai.msrv.gaterook.core.notifications.ApprovalNotice;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import ai.msrv.gaterook.core.platform.MaskPolicy;
import ai.msrv.gaterook.core.platform.Masker;
import ai.msrv.gaterook.core.platform.Taint;
import ai.msrv.gaterook.core.policy.PolicyService;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Turns an approval request into what channels may show of it ({@link ApprovalNotice}): never the agent's note, every
 * text through the masker with the environment's policy, the exact action with its invisible characters as visible
 * escapes. A plugin's or an MCP server's tool shows the agent's own arguments (as the window does first), without the
 * {@code note} argument.
 */
@Component
public class Notices {

  private final ApprovalService approvals;
  private final PolicyService policies;
  private final Masker masker;

  Notices(ApprovalService approvals, PolicyService policies, Masker masker) {
    this.approvals = approvals;
    this.policies = policies;
    this.masker = masker;
  }

  public Optional<ApprovalNotice> of(String approvalId) {
    return approvals.find(approvalId).map(this::of);
  }

  public ApprovalNotice of(ApprovalView a) {
    MaskPolicy mask = policies.maskPolicy(a.env());
    String toolKey = a.toolKey() == null ? "" : a.toolKey();
    boolean plugin = toolKey.startsWith("plugin:");
    boolean external = plugin || toolKey.startsWith("mcp:");
    String kind;
    String language;
    String raw;
    boolean partial = false;
    ActionPreview p = a.action();
    if (external || p == null) {
      JsonNode args = a.call() == null ? null : a.call().path("arguments");
      ObjectNode own = args != null && args.isObject() ? ((ObjectNode) args).deepCopy()
          : tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
      partial = own.remove("note") != null;
      kind = "arguments";
      language = "json";
      raw = CanonicalJson.text(own);
    } else {
      kind = p.kind() == null ? "text" : p.kind().name().toLowerCase(Locale.ROOT);
      language = p.language();
      raw = p.text() == null ? "" : p.text();
    }
    Masker.MaskedText masked = masker.text(raw, new Taint(), mask);
    boolean secretLike = masked.outcome().masked();
    ActionInspector.Inspection inspection = ActionInspector.inspect(raw, language);
    StringBuilder visible = new StringBuilder();
    inspection.segments().forEach(s -> visible.append(s.v()));
    ApprovalNotice.Action action = new ApprovalNotice.Action(kind, language, secretLike ? "" : visible.toString(),
        raw.length(), secretLike, inspection.hiddenChars(), inspection.confusables(), partial);
    String client = a.call() == null ? null : text(a.call().path("origin").path("client"));
    // The one line a channel shows when the action itself isn't (looks like a secret, too long). A plugin's or MCP
    // server's own line is its text about the agent's arguments — it may repeat the note, which no channel carries —
    // so theirs is the core's; every line shows its invisible characters as escapes, like the action.
    String summary = external ? a.tool() + (a.connectionName() == null ? "" : " on " + a.connectionName())
        : p == null ? null : p.summary();
    return new ApprovalNotice(a.id(), mask(a.tool(), a.env()), toolKey, mask(a.connectionName(), a.env()), a.env(),
        mask(a.endpointSlug(), a.env()), client, mask(a.clientLabel(), a.env()), action,
        summary == null ? null : visible(mask(cut(summary, 120), a.env())), a.ruleId(), a.commentPolicy(),
        a.createdAt(), a.expiresAt(), a.digest(), plugin);
  }

  /** {@code text} with its invisible and bidi characters as visible escapes ({@code ‹U+202E›}). */
  static String visible(String text) {
    StringBuilder out = new StringBuilder();
    ActionInspector.inspect(text, null).segments().forEach(s -> out.append(s.v()));
    return out.toString();
  }

  /** {@code text} as a channel may show it on {@code env}: through the masker with that environment's policy. */
  public String mask(String text, Env env) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    return masker.text(text, new Taint(), policies.maskPolicy(env)).text();
  }

  /** The first line of the action, ≤ {@code max} characters, "…" when cut: what a desktop notification has room for. */
  public static String firstLine(String text, int max) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    String line = text.strip();
    int nl = line.indexOf('\n');
    boolean more = nl >= 0;
    if (more) {
      line = line.substring(0, nl).stripTrailing();
    }
    if (line.length() > max) {
      int end = max - 1;
      if (Character.isHighSurrogate(line.charAt(end - 1))) {
        end--;
      }
      return line.substring(0, end) + "…";
    }
    return more ? line + " …" : line;
  }

  private static String text(JsonNode n) {
    return n != null && n.isString() ? n.asString() : null;
  }

  private static String cut(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
  }
}
