package com.gaterook.core.notifications.internal;

import com.gaterook.connector.api.ActionPreview;
import com.gaterook.connector.api.Env;
import com.gaterook.core.approvals.ActionInspector;
import com.gaterook.core.approvals.ApprovalService;
import com.gaterook.core.approvals.ApprovalView;
import com.gaterook.core.notifications.ApprovalNotice;
import com.gaterook.core.platform.Caller;
import com.gaterook.core.platform.CanonicalJson;
import com.gaterook.core.platform.MaskPolicy;
import com.gaterook.core.platform.Masker;
import com.gaterook.core.platform.Taint;
import com.gaterook.core.policy.PolicyService;
import java.util.ArrayList;
import java.util.List;
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
    // Who asked: the family the credential implies. A named token implies none, and the family its call recorded is
    // only the name the client gives itself, so a channel names the token's label instead (ApprovalNotice.asker).
    String client = a.call() == null || a.principalKind() == null || Caller.familyReported(a.principalKind()) ? null
        : text(a.call().path("origin").path("client"));
    // The one line a channel shows when the action itself isn't (looks like a secret, too long). A plugin's or MCP
    // server's own line is its text about the agent's arguments — it may repeat the note, which no channel carries —
    // so theirs is the core's; every line shows its invisible characters as escapes, like the action.
    String summary = external ? a.tool() + (a.connectionName() == null ? "" : " on " + a.connectionName())
        : p == null ? null : p.summary();
    // What the app shows with the text: the target (a multi-database connection's database), the connector's fields
    // (a template's parameter values), its impact (the estimated rows, the transaction) and its warnings. A plugin's
    // or MCP server's fields are its own words: not carried; the warnings are the connector's, also of an MCP server's
    // tool, and a person approving from a message reads them there as in the app.
    List<ApprovalNotice.Detail> details = new ArrayList<>();
    if (!external && p != null) {
      if (p.target() != null && !p.target().isBlank()) {
        details.add(detail("Target", p.target(), mask));
      }
      details(details, p.fields(), mask);
      details(details, p.impact(), mask);
    }
    for (String warning : p == null || p.warnings() == null ? List.<String>of() : p.warnings()) {
      if (warning != null && !warning.isBlank()) {
        details.add(detail("Warning", warning, mask));
      }
    }
    return new ApprovalNotice(a.id(), mask(a.tool(), a.env()), toolKey, mask(a.connectionName(), a.env()), a.env(),
        mask(a.endpointSlug(), a.env()), client, mask(a.clientLabel(), a.env()), action,
        summary == null ? null : visible(mask(cut(summary, 120), a.env())), a.ruleId(), a.commentPolicy(),
        a.createdAt(), a.expiresAt(), a.digest(), plugin, details);
  }

  private void details(List<ApprovalNotice.Detail> out, List<ActionPreview.Field> fields, MaskPolicy mask) {
    for (ActionPreview.Field f : fields == null ? List.<ActionPreview.Field>of() : fields) {
      if (f != null && f.value() != null && !f.value().isBlank()) {
        out.add(detail(f.label() == null ? "" : f.label(), f.value(), mask));
      }
    }
  }

  /** A detail as a channel may show it: masked, its invisible characters as escapes, and what was found in it. */
  private ApprovalNotice.Detail detail(String label, String value, MaskPolicy mask) {
    String masked = masker.text(value, new Taint(), mask).text();
    ActionInspector.Inspection inspection = ActionInspector.inspect(masked, null);
    StringBuilder visible = new StringBuilder();
    inspection.segments().forEach(s -> visible.append(s.v()));
    return new ApprovalNotice.Detail(visible(masker.text(label, new Taint(), mask).text()), visible.toString(),
        inspection.hiddenChars(), inspection.confusables());
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
