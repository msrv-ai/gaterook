package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ActionPreview;
import ai.msrv.gaterook.connector.api.ConnectionConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * What the approver sees and what runs after approval (report 06 §3.5, ARCHITECTURE.md §9 "Approve the exact
 * action"): the text is the exact line the host gets — with {@code cd '<folder>' &&} and the remote deadline when
 * they apply — and the payload stored with the request is executed instead of the arguments.
 */
final class SshActions {

  /** The remote deadline of a command without a timeout of its own: a stray process must not outlive the day. */
  static final int REMOTE_DEFAULT_SECONDS = 300;

  private SshActions() {}

  static int remoteSeconds(Runbook rb) {
    return rb.timeoutSeconds() > 0 ? rb.timeoutSeconds() : REMOTE_DEFAULT_SECONDS;
  }

  /** {@code ssh_exec}'s line: the command byte for byte, wrapped in the remote deadline when the connection wants it. */
  static String execWire(String command, SshSettings s) {
    return s.advanced().remoteDeadline()
        ? "timeout -k 5 " + REMOTE_DEFAULT_SECONDS + " sh -c " + ShellQuote.quote(command) : command;
  }

  static ObjectNode runbookPayload(Runbooks.Entry entry, Map<String, JsonNode> values, String wire) {
    ObjectNode p = JsonNodeFactory.instance.objectNode();
    p.put("tool", SshTools.RUN);
    p.put("runbook", entry.runbook().name());
    p.put("templateId", entry.templateId());
    p.put("version", entry.version());
    ObjectNode params = p.putObject("params");
    values.forEach(params::set);
    p.put("wire", wire);
    return p;
  }

  static ObjectNode execPayload(String wire) {
    ObjectNode p = JsonNodeFactory.instance.objectNode();
    p.put("tool", SshTools.EXEC);
    p.put("wire", wire);
    return p;
  }

  static ActionPreview runbookPreview(Runbook rb, Map<String, JsonNode> values, String wire, String target,
      ConnectionConfig cfg, SshSettings s) {
    List<ActionPreview.Field> fields = new ArrayList<>();
    String about = rb.description() == null ? "" : " · " + rb.description();
    fields.add(new ActionPreview.Field("Runbook", rb.name() + about + " · "
        + (rb.readOnly() ? "read only" : "changes state")));
    List<String> shown = new ArrayList<>();
    values.forEach((k, v) -> shown.add(k + "=" + (v.isString() ? v.asString() : v.toString())));
    fields.add(new ActionPreview.Field("Parameters", shown.isEmpty() ? "none" : String.join(", ", shown)));
    fields.add(new ActionPreview.Field("Command", wire));
    if (s != null) {
      fields.add(new ActionPreview.Field("Runs as", SshLogins.Login.describe(s)));
    }
    List<String> warnings = new ArrayList<>();
    if (RunbookCompiler.changingWords(rb.command()).contains("sudo")) {
      warnings.add("Uses sudo");
    }
    return ActionPreview.of(ActionPreview.Kind.RUNBOOK, "sh", wire, target, "runbook " + rb.name() + " on "
            + cfg.name())
        .withFields(fields).withWarnings(warnings).withFacets(Map.of("runbook", rb.name()));
  }

  static ActionPreview execPreview(String command, String wire, String target, ConnectionConfig cfg, SshSettings s) {
    List<ActionPreview.Field> fields = new ArrayList<>();
    fields.add(new ActionPreview.Field("Runs as", SshLogins.Login.describe(s)));
    return ActionPreview.of(ActionPreview.Kind.COMMAND, "sh", wire, target, "ssh_exec on " + cfg.name())
        .withFields(fields).withWarnings(execWarnings(command));
  }

  /** Hints for the approver, not policy (report 06 §3.5): a shell line can't be classified reliably. */
  static List<String> execWarnings(String command) {
    List<String> w = new ArrayList<>();
    String lower = command.toLowerCase(Locale.ROOT);
    if (command.indexOf('\n') >= 0) {
      w.add("Several lines: every line runs");
    }
    if (lower.matches("(?s)(.*[\\s;&|(])?sudo\\s.*")) {
      w.add("Uses sudo");
    }
    if (lower.matches("(?s).*\\|\\s*(ba|z|da|k)?sh\\b.*")) {
      w.add("Pipes into a shell");
    }
    if (lower.matches("(?s).*\\brm\\s+(-[a-z]*r|-[a-z]*f|--recursive|--force).*")) {
      w.add("Deletes recursively or by force");
    }
    if (RunbookCompiler.changingWords(command).contains(">") || lower.matches("(?s).*\\btee\\b.*")) {
      w.add("Writes to a file");
    }
    if (command.length() > 500) {
      w.add("Long command: " + command.length() + " characters");
    }
    return w;
  }
}
