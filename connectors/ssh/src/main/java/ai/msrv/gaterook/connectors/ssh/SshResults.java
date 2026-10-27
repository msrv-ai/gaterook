package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectionConfig;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The answer of a command (report 06 §3.3). Output from a host is untrusted content: it is decoded as UTF-8 with
 * replacement and ANSI or control sequences become visible escapes; secrets and PII are masked by the core after
 * this. A command that ran and exited is data, whatever its exit code ({@code systemctl status} exits 3 for a stopped
 * service), so {@code isError} is only for a run that was stopped: at the deadline, by cancellation or by a signal
 * the host sent.
 */
final class SshResults {

  private SshResults() {}

  /**
   * @param runbook the runbook's name, or {@code null} for {@code ssh_exec}
   * @param maxOut  the stdout limit, for the "Output cut at" line
   */
  static ToolResult of(ConnectionConfig cfg, String runbook, String wire, SshExec.Result r, long maxOut) {
    String stdout = OutputSanitizer.decode(r.stdout());
    String stderr = OutputSanitizer.decode(r.stderr());
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    s.put("connection", cfg.name());
    if (runbook != null) {
      s.put("runbook", runbook);
    }
    s.put("command", wire);
    if (r.exitStatus() == null) {
      s.putNull("exitCode");
    } else {
      s.put("exitCode", r.exitStatus());
    }
    if (r.exitSignal() == null) {
      s.putNull("exitSignal");
    } else {
      s.put("exitSignal", r.exitSignal());
    }
    s.put("stdout", stdout);
    s.put("stderr", stderr);
    s.put("stdoutBytes", r.stdoutTotal());
    s.put("stderrBytes", r.stderrTotal());
    boolean truncated = r.truncatedReason() != null;
    s.put("truncated", truncated);
    if (truncated) {
      s.put("truncatedReason", r.truncatedReason());
    } else {
      s.putNull("truncatedReason");
    }
    s.put("timedOut", r.timedOut());
    s.put("durationMs", r.durationMs());

    StringBuilder text = new StringBuilder();
    text.append(runbook == null ? SshTools.EXEC : runbook).append(" on ").append(cfg.name()).append(" (")
        .append(cfg.env().id()).append(") · ");
    if (r.cancelled()) {
      text.append("cancelled");
    } else if (r.timedOut()) {
      text.append("stopped at the timeout");
    } else if (r.exitStatus() != null) {
      text.append("exit ").append(r.exitStatus());
    } else if (r.exitSignal() != null) {
      text.append("killed by ").append(r.exitSignal());
    } else {
      text.append("no exit status");
    }
    text.append(" · ").append(r.durationMs()).append(" ms\n");
    text.append(stdout);
    if (!stderr.isEmpty()) {
      if (!stdout.isEmpty() && !stdout.endsWith("\n")) {
        text.append('\n');
      }
      text.append("stderr:\n").append(stderr);
    }
    if ("output_limit".equals(r.truncatedReason())) {
      text.append("\nOutput cut at ").append(kb(maxOut)).append(" of ≥ ").append(kb(Math.max(r.stdoutTotal(),
          r.stderrTotal()))).append(". Narrow it, e.g. --tail.");
    } else if (r.timedOut() && !r.cancelled()) {
      text.append("\nIt was stopped with TERM (KILL 2 s later) at the time limit. Narrow it or raise the timeout.");
    }
    boolean stopped = r.timedOut() || r.cancelled()
        || (r.exitSignal() != null && !"output_limit".equals(r.truncatedReason()));
    ToolResult result = new ToolResult(List.of(ToolResult.Content.text(text.toString())), s, stopped, null, false,
        null, List.of(), 0, 0, null);
    return truncated ? result.truncated(r.truncatedReason()) : result;
  }

  private static String kb(long bytes) {
    if (bytes >= 1024 * 1024) {
      return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
    return (bytes / 1024) + " KB";
  }
}
