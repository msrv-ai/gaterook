package ai.msrv.gaterook.connectors.logs;

import java.util.List;
import tools.jackson.databind.node.ArrayNode;

/**
 * What one backend query returned.
 *
 * @param entries      in the order asked for (newest first for a search, oldest first for a tail)
 * @param more         the backend may hold more entries of the window than it returned: it filled the requested
 *                     count, it stopped at its page budget, or it ran out of time
 * @param continuation the backend's own token to resume this exact window after the last returned entry (GCP
 *                     {@code pageToken}, CloudWatch {@code nextToken}), or {@code null}
 * @param partial      {@code "partial"} (Insights didn't finish) or {@code "timeout"} (the deadline cut the paging),
 *                     or {@code null}
 * @param aggregate    a metric or {@code stats} answer instead of entries, or {@code null}
 */
record Page(List<LogEntry> entries, boolean more, String continuation, String partial, Aggregate aggregate,
    Stats stats) {

  Page {
    entries = List.copyOf(entries);
  }

  static Page of(List<LogEntry> entries, boolean more, String continuation, Stats stats) {
    return new Page(entries, more, continuation, null, null, stats);
  }

  Page withPartial(String reason) {
    return new Page(entries, true, continuation, reason, aggregate, stats);
  }

  /** A table answer: LogQL metric queries ({@code matrix}), Insights {@code stats}. */
  record Aggregate(List<String> columns, ArrayNode rows) {}

  /**
   * What the backend reports it did, for the answer and the audit: Insights bills by bytes scanned.
   *
   * @param bytesScanned   bytes the backend read, or -1 when it doesn't say
   * @param recordsScanned records it looked at, or -1
   */
  record Stats(int requests, long bytesScanned, long recordsScanned) {
    static final Stats NONE = new Stats(0, -1, -1);

    Stats plus(Stats o) {
      return new Stats(requests + o.requests, sum(bytesScanned, o.bytesScanned), sum(recordsScanned, o.recordsScanned));
    }

    static Stats requests(int n) {
      return new Stats(n, -1, -1);
    }

    private static long sum(long a, long b) {
      return a < 0 ? b : b < 0 ? a : a + b;
    }
  }
}
