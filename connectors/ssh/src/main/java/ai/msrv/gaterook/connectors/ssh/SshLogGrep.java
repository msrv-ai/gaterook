package ai.msrv.gaterook.connectors.ssh;

import ai.msrv.gaterook.connector.api.ConnectorException;
import ai.msrv.gaterook.connector.api.ExecContext;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.sshd.client.session.ClientSession;

/** Decodes byte-offset-prefixed grep candidates without turning raw matches into public scan progress. */
final class SshLogGrep {
  record Line(long offset, long endOffset, byte[] textBytes) {}

  record SourceWindow(List<Line> lines, long start, long end, long lastCompleteEnd, boolean startsAtLineBoundary,
      SshLogCommands.Snapshot before, SshLogCommands.Snapshot after) {}

  private SshLogGrep() {}

  static SourceWindow read(ClientSession session, SshLogSettings settings, String file, long offset, int length,
      String expression, ExecContext ctx) throws ConnectorException {
    return decode(SshLogCommands.grep(session, settings, file, offset, length, expression, ctx));
  }

  /** Only complete physical source lines are returned; a cut trailing line is reread from lastCompleteEnd. */
  static SourceWindow decode(SshLogCommands.Result result) throws ConnectorException {
    byte[] data = result.data();
    List<Line> lines = new ArrayList<>();
    int at = 0;
    long previousEnd = result.offset();
    while (at < data.length) {
      long relative = 0;
      int digits = 0;
      while (at < data.length && data[at] != ':') {
        int digit = data[at++] - '0';
        if (digit < 0 || digit > 9 || ++digits > 10) throw invalid();
        relative = relative * 10 + digit;
      }
      if (digits == 0 || at == data.length || relative >= result.endOffset() - result.offset()) throw invalid();
      int begin = ++at;
      while (at < data.length && data[at] != '\n') at++;
      if (at == data.length) throw invalid();
      long start = result.offset() + relative;
      long end = start + (at - begin) + 1L;
      // grep writes a final LF even when the last physical source line has none.
      if (start < previousEnd || end > result.endOffset() + 1) throw invalid();
      previousEnd = Math.min(end, result.endOffset());
      boolean startsComplete = relative != 0 || result.startsAtLineBoundary();
      boolean endsComplete = end <= result.lastCompleteEnd()
          || (result.endOffset() == result.before().size() && end == result.endOffset() + 1);
      if (startsComplete && endsComplete) {
        lines.add(new Line(start, Math.min(end, result.endOffset()), Arrays.copyOfRange(data, begin, at)));
      }
      at++;
    }
    return new SourceWindow(List.copyOf(lines), result.offset(), result.endOffset(), result.lastCompleteEnd(),
        result.startsAtLineBoundary(), result.before(), result.after());
  }

  private static ConnectorException invalid() {
    return ConnectorException.resourceError("The SSH server returned invalid log search records.", null);
  }
}
