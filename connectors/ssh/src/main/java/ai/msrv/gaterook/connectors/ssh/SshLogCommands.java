package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import org.apache.sshd.client.session.ClientSession;

/** Fixed, read-only log commands. File names and search expressions are data, never shell programs. */
final class SshLogCommands {
  private static final long MAX_TRANSFER = 64L * 1024 * 1024;
  static final int GREP_TRANSFER_BYTES = 1024 * 1024;
  private static final long MAX_POSITION = 9_007_199_254_740_991L;
  private static final int STDERR_BYTES = 4096;
  private static final Duration FINISH_MARGIN = Duration.ofMillis(250);
  private static final String METADATA = "GATEROOK_LOG_V1:";
  private static final String WINDOW = "GATEROOK_LOG_WINDOW:";

  record Snapshot(String identity, long size, String modified) {}

  /** Byte positions and complete-line bounds refer to the source window, independent of grep candidates. */
  record Result(byte[] data, Snapshot before, Snapshot after, long offset, long endOffset,
      boolean startsAtLineBoundary, boolean endsAtLineBoundary, long lastCompleteEnd, long firstCompleteStart) {}

  record Range(Snapshot snapshot, long start, long end) {}

  enum Kind { INSPECT, BYTES, HEAD, TAIL, LINES, BOUNDS, GREP }

  private SshLogCommands() {}

  static Snapshot inspect(ClientSession session, SshLogSettings settings, String file, ExecContext ctx)
      throws ConnectorException {
    return execute(session, settings, file, Kind.INSPECT, 0, 0, 0, null, ctx).before();
  }

  static Result bytes(ClientSession session, SshLogSettings settings, String file, long offset, int length,
      ExecContext ctx) throws ConnectorException {
    return execute(session, settings, file, Kind.BYTES, offset, length, length, null, ctx);
  }

  /** Select the last N records within a bounded source window; a partial leading record is marked explicitly. */
  static Result tail(ClientSession session, SshLogSettings settings, String file, int lines, long maxBytes,
      ExecContext ctx) throws ConnectorException {
    return execute(session, settings, file, Kind.TAIL, lines, 0, maxBytes, null, ctx);
  }

  static Result head(ClientSession session, SshLogSettings settings, String file, int lines, long maxBytes,
      ExecContext ctx) throws ConnectorException {
    return execute(session, settings, file, Kind.HEAD, lines, 0, maxBytes, null, ctx);
  }

  static Result lines(ClientSession session, SshLogSettings settings, String file, long first, long last,
      long maxBytes, ExecContext ctx) throws ConnectorException {
    return execute(session, settings, file, Kind.LINES, first, last, maxBytes, null, ctx);
  }

  /** One-based inclusive line range; last=0 extends to the snapshot's end. No file contents are transferred. */
  static Range bounds(ClientSession session, SshLogSettings settings, String file, long first, long last,
      ExecContext ctx) throws ConnectorException {
    Result result = execute(session, settings, file, Kind.BOUNDS, first, last, 0, null, ctx);
    return new Range(result.before(), result.offset(), result.endOffset());
  }

  /** The expression must be a conservative candidate filter; masked matching remains authoritative locally. */
  static Result grep(ClientSession session, SshLogSettings settings, String file, long offset, int length,
      String expression, ExecContext ctx) throws ConnectorException {
    return execute(session, settings, file, Kind.GREP, offset, length, GREP_TRANSFER_BYTES, expression, ctx);
  }

  private static Result execute(ClientSession session, SshLogSettings settings, String file, Kind kind, long first,
      long second, long maxBytes, String expression, ExecContext ctx) throws ConnectorException {
    String wire = command(settings, file, kind, first, second, maxBytes, expression);
    Duration remaining = ctx.remaining().minus(FINISH_MARGIN);
    if (ctx.cancelled() || Thread.currentThread().isInterrupted() || remaining.isNegative() || remaining.isZero()) {
      throw ConnectorException.timeout("The SSH log operation was cancelled or reached its deadline.");
    }
    SshExec.Result result;
    try {
      result = SshExec.run(session, wire, remaining, maxBytes + 1, STDERR_BYTES, ctx);
    } catch (SshExec.ChannelRefused e) {
      throw ConnectorException.unavailable("The SSH server refused the log command channel. Try again shortly.", e);
    }
    if (result.timedOut() || result.cancelled() || ctx.cancelled()) {
      throw ConnectorException.timeout("The SSH log command did not finish before its deadline.");
    }
    if (result.brokenOff()) {
      throw ConnectorException.unavailable("The SSH connection closed before the log command finished.", null);
    }
    if (result.truncatedReason() != null || (kind != Kind.GREP && result.stdout().length > maxBytes)) {
      throw ConnectorException.badInput("The selected log lines exceed the transfer limit. Request fewer lines or a smaller range.");
    }
    if (result.exitStatus() == null || result.exitStatus() != 0) {
      throw commandFailure(result.exitStatus());
    }
    return decode(result.stdout(), result.stderr());
  }

  static String command(SshLogSettings settings, String file, Kind kind, long first, long second, long maxBytes)
      throws ConnectorException {
    return command(settings, file, kind, first, second, maxBytes, null);
  }

  /** Package-visible for tests that run generated programs in disposable local directories. */
  static String command(SshLogSettings settings, String file, Kind kind, long first, long second, long maxBytes,
      String expression) throws ConnectorException {
    if (maxBytes < 0 || maxBytes > MAX_TRANSFER || first < 0 || first > MAX_POSITION || second < 0 || second > MAX_POSITION
        || ((kind == Kind.BYTES || kind == Kind.GREP) && second > MAX_TRANSFER)
        || (kind == Kind.BYTES && second > maxBytes)
        || ((kind == Kind.HEAD || kind == Kind.TAIL) && first == 0)
        || ((kind == Kind.LINES || kind == Kind.BOUNDS) && (first == 0 || (second != 0 && second < first)))
        || (kind == Kind.GREP && (expression == null || expression.indexOf('\0') >= 0 || expression.length() > 8192))) {
      throw ConnectorException.badInput("The log command range or transfer limit is invalid.");
    }
    String path = settings.allowed(file);
    String checks = checks(path);
    String utilities = switch (kind) {
      case INSPECT -> "stat";
      case BOUNDS -> "stat head awk";
      case LINES -> "stat head tail awk";
      case BYTES, HEAD, TAIL -> "stat head tail od wc";
      case GREP -> "stat head tail od wc grep cat";
    };
    StringBuilder script = new StringBuilder("LC_ALL=C; export LC_ALL; set -f\n")
        .append("for gr_tool in ").append(utilities).append("; do command -v \"$gr_tool\" >/dev/null 2>&1 || exit 43; done\n")
        .append("gr_stat() { stat -Lc '%d:%i:%s:%Y' \"$1\" 2>/dev/null || stat -Lf '%d:%i:%z:%m' \"$1\" 2>/dev/null; }\n")
        .append("gr_abort() { printf 'GATEROOK_LOG_ERROR:%s\\n' \"$1\" >&2; exit \"$1\"; }\n")
        .append("gr_reopen() { exec 4<").append(ShellQuote.quote(path)).append(" || gr_abort 42; ")
        .append("[ -f /dev/fd/4 ] || gr_abort 42; ")
        .append("gr_read=$(gr_stat /dev/fd/4) || gr_abort 43; gr_read=$gr_device:${gr_read#*:}; ")
        .append("gr_read_path=$(gr_stat ").append(ShellQuote.quote(path)).append(") || gr_abort 43; ")
        .append("[ \"${gr_read%:*:*}\" = \"${gr_before%:*:*}\" ] && [ \"${gr_read_path%:*:*}\" = \"${gr_before%:*:*}\" ] || gr_abort 44; }\n")
        .append("gr_window() { [ \"$gr_end\" -gt \"$gr_start\" ] || return 0; gr_reopen; tail -c +$((gr_start + 1)) <&4 | head -c $((gr_end - gr_start)); }\n")
        .append("gr_byte() { gr_reopen; gr_decimal=$(tail -c +$(( $1 + 1 )) <&4 | head -c 1 | od -An -tu1); ")
        .append("set -- $gr_decimal; [ \"$#\" -eq 1 ] || gr_abort 45; case $1 in *[!0-9]*) gr_abort 45;; esac; printf '%s' \"$1\"; }\n")
        .append(checks)
        .append("[ -f ").append(ShellQuote.quote(path)).append(" ] || exit 42\n")
        .append("gr_original=$(gr_stat ").append(ShellQuote.quote(path)).append(") || exit 43\n")
        .append("exec 3<").append(ShellQuote.quote(path)).append(" || exit 42\n")
        .append("[ -f /dev/fd/3 ] || exit 42\n")
        .append("gr_open=$(gr_stat /dev/fd/3) || exit 43\n")
        .append("gr_path=$(gr_stat ").append(ShellQuote.quote(path)).append(") || exit 43\n")
        .append("[ \"${gr_original%:*:*}\" = \"${gr_path%:*:*}\" ] || exit 44\n")
        // BSD fdescfs exposes its own device id for /dev/fd/N; use the checked path's device with the open inode.
        .append("gr_device=${gr_path%%:*}; gr_before=$gr_device:${gr_open#*:}\n")
        .append("[ \"${gr_before%:*:*}\" = \"${gr_path%:*:*}\" ] || exit 44\n")
        .append("gr_size=${gr_before%:*}; gr_size=${gr_size##*:}\n")
        .append("case $gr_size in ''|*[!0-9]*) exit 43;; esac\n")
        .append("printf '").append(METADATA).append("%s\\n' \"$gr_before\" >&2\n");
    switch (kind) {
      case INSPECT -> script.append("gr_start=0; gr_end=0\n");
      case BYTES, GREP -> script.append("[ ").append(first).append(" -le \"$gr_size\" ] || exit 46\n")
          .append("gr_start=").append(first).append("; gr_end=$((gr_start + ").append(second).append("))\n")
          .append("[ \"$gr_end\" -le \"$gr_size\" ] || gr_end=$gr_size\n");
      case TAIL -> script.append("gr_start=$((gr_size - ").append(maxBytes).append(")); [ \"$gr_start\" -ge 0 ] || gr_start=0\n")
          .append("gr_end=$gr_size\n")
          .append("gr_tail_bytes=$(gr_window | tail -n ").append(first).append(" | wc -c) || exit 45\n")
          .append("gr_start=$((gr_size - gr_tail_bytes))\n");
      case HEAD -> script.append("gr_start=0; gr_end=").append(maxBytes).append("; [ \"$gr_end\" -le \"$gr_size\" ] || gr_end=$gr_size\n")
          .append("gr_head_bytes=$(gr_window | head -n ").append(first).append(" | wc -c) || exit 45\n")
          .append("gr_end=$((gr_head_bytes))\n");
      case LINES, BOUNDS -> script.append("if [ \"$gr_size\" -eq 0 ]; then gr_range=0:0; else\n")
          .append("gr_reopen\ngr_range=$(head -c \"$gr_size\" <&4 | awk -v gr_size=\"$gr_size\" -v gr_first=")
          .append(first).append(" -v gr_last=").append(second).append(" ")
          .append(ShellQuote.quote("BEGIN { gr_pos=0; gr_start=-1; gr_end=gr_size } "
              + "{ if (NR==gr_first) gr_start=gr_pos; gr_pos+=length($0)+1; if (gr_pos>gr_size) gr_pos=gr_size; "
              + "if (gr_last>0 && NR>=gr_last) { gr_end=gr_pos; exit } } "
              + "END { if (gr_start<0) gr_start=gr_end=gr_size; printf \"%.0f:%.0f\\n\",gr_start,gr_end }"))
          .append(") || exit 45\nfi\n")
          .append("gr_start=${gr_range%:*}; gr_end=${gr_range#*:}\n");
    }
    if (kind == Kind.INSPECT || kind == Kind.BOUNDS || kind == Kind.LINES) {
      script.append("gr_start_boundary=1; gr_end_boundary=1; gr_complete=$gr_end\n");
    } else {
      script.append("gr_start_boundary=1; gr_end_boundary=1; gr_complete=$gr_end\n")
          .append("if [ \"$gr_start\" -gt 0 ]; then gr_previous=$(gr_byte $((gr_start - 1))); [ \"$gr_previous\" = 10 ] || gr_start_boundary=0; fi\n")
          .append("if [ \"$gr_end\" -gt \"$gr_start\" ] && [ \"$gr_end\" -lt \"$gr_size\" ]; then\n")
          .append("  gr_last=$(gr_byte $((gr_end - 1)))\n")
          .append("  if [ \"$gr_last\" != 10 ]; then gr_end_boundary=0; gr_last_bytes=$(gr_window | tail -n 1 | wc -c); gr_complete=$((gr_end - gr_last_bytes)); fi\n")
          .append("fi\n");
    }
    script.append("gr_first=$gr_start\n")
        .append("if [ \"$gr_start_boundary\" -eq 0 ]; then gr_first_bytes=$(gr_window | head -n 1 | wc -c); gr_first=$((gr_start + gr_first_bytes)); fi\n")
        .append("printf '").append(WINDOW).append("%s:%s:%s:%s:%s:%s\\n' \"$gr_start\" \"$gr_end\" \"$gr_start_boundary\" \"$gr_end_boundary\" \"$gr_complete\" \"$gr_first\" >&2\n");
    switch (kind) {
      case INSPECT, BOUNDS -> { }
      case BYTES, HEAD, TAIL -> script.append("gr_window\n[ $? -eq 0 ] || exit 45\n");
      case LINES -> script.append("gr_window | head -c ").append(maxBytes + 1).append("\n[ $? -eq 0 ] || exit 45\n");
      // Keep one overflow byte, drain the remaining candidates, and preserve grep failures in stderr framing.
      case GREP -> script.append("gr_window | { grep -a -b -E -e ").append(ShellQuote.quote(expression))
          .append("; gr_status=$?; [ \"$gr_status\" -le 1 ] || gr_abort 45; } | { head -c ")
          .append(maxBytes + 1).append("; cat >/dev/null; }\n[ $? -eq 0 ] || exit 45\n");
    }
    script.append(checks)
        .append("gr_open_after=$(gr_stat /dev/fd/3) || exit 43\n")
        .append("gr_after=$gr_device:${gr_open_after#*:}\n")
        .append("gr_path=$(gr_stat ").append(ShellQuote.quote(path)).append(") || exit 43\n")
        .append("[ \"${gr_before%:*:*}\" = \"${gr_after%:*:*}\" ] && [ \"${gr_before%:*:*}\" = \"${gr_path%:*:*}\" ] || exit 44\n")
        .append("printf '").append(METADATA).append("%s\\n' \"$gr_after\" >&2\n");
    return "sh -c " + ShellQuote.quote(script.toString());
  }

  private static String checks(String path) {
    StringBuilder checks = new StringBuilder();
    String prefix = "";
    for (String component : path.substring(1).split("/")) {
      prefix += "/" + component;
      checks.append("[ ! -L ").append(ShellQuote.quote(prefix)).append(" ] || exit 41\n");
    }
    return checks.toString();
  }

  static Result decode(byte[] stdout, byte[] stderr) throws ConnectorException {
    String[] lines = new String(stderr, StandardCharsets.US_ASCII).split("\\n", -1);
    if (lines.length != 4 || !lines[3].isEmpty()) {
      throw ConnectorException.resourceError("The SSH server returned an invalid log metadata response.", null);
    }
    Snapshot before = snapshot(lines[0]);
    Snapshot after = snapshot(lines[2]);
    if (!lines[1].startsWith(WINDOW) || !lines[1].substring(WINDOW.length()).matches("[0-9]+:[0-9]+:[01]:[01]:[0-9]+:[0-9]+")) {
      throw ConnectorException.resourceError("The SSH server returned invalid log window metadata.", null);
    }
    String[] fields = lines[1].substring(WINDOW.length()).split(":");
    long start;
    long end;
    long complete;
    long firstComplete;
    try {
      start = Long.parseLong(fields[0]);
      end = Long.parseLong(fields[1]);
      complete = Long.parseLong(fields[4]);
      firstComplete = Long.parseLong(fields[5]);
    } catch (NumberFormatException e) {
      throw ConnectorException.resourceError("The SSH server returned invalid log byte positions.", null);
    }
    if (start > end || end > before.size() || complete < start || complete > end
        || firstComplete < start || firstComplete > end) {
      throw ConnectorException.resourceError("The SSH server returned invalid log byte positions.", null);
    }
    if (!before.identity().equals(after.identity()) || after.size() < before.size()
        || (before.size() == after.size() && !before.modified().equals(after.modified()))) {
      throw ConnectorException.badInput("The selected log file was replaced, truncated or rewritten. Start the query again.");
    }
    return new Result(Arrays.copyOf(stdout, stdout.length), before, after, start, end,
        fields[2].equals("1"), fields[3].equals("1"), complete, firstComplete);
  }

  private static Snapshot snapshot(String line) throws ConnectorException {
    if (!line.startsWith(METADATA) || !line.substring(METADATA.length()).matches("[0-9]+:[0-9]+:[0-9]+:-?[0-9]+")) {
      throw ConnectorException.resourceError("The SSH server returned invalid log file metadata.", null);
    }
    String[] fields = line.substring(METADATA.length()).split(":");
    try {
      return new Snapshot(fields[0] + ":" + fields[1], Long.parseLong(fields[2]), fields[3]);
    } catch (NumberFormatException e) {
      throw ConnectorException.resourceError("The SSH server returned an invalid log file size.", null);
    }
  }

  private static ConnectorException commandFailure(Integer status) {
    if (status != null && status == 41) {
      return ConnectorException.denied("Symbolic links are not allowed in selected log paths.");
    }
    if (status != null && status == 42) {
      return ConnectorException.resourceError("The selected log path must be a readable regular file.", null);
    }
    if (status != null && status == 44) {
      return ConnectorException.badInput("The selected log file was replaced or rotated. Start the query again.");
    }
    if (status != null && status == 46) {
      return ConnectorException.badInput("The byte offset is past the end of this log file. Start the query again.");
    }
    return ConnectorException.resourceError("The SSH log command failed. The account needs a POSIX shell and the stat, head, tail, awk, od, wc, grep and cat utilities.", null);
  }
}
