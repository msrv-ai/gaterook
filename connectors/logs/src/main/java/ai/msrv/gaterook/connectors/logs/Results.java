package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.ToolResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The answer of {@code logs_search} and {@code logs_tail} (report 07 §3.3): {@code structuredContent} of the tools'
 * output schema and the same entries as lines of text,
 * {@code 2026-10-22T12:29:58.123Z ERROR [namespace_name=app pod_name=api-7d9] payment failed}, then what to do next.
 *
 * <p>The connector cuts to the response size itself, by whole entries, before it computes the cursor: if the core
 * had to cut entries, the cursor would point past entries the agent never got. A quarter of the size limit is kept
 * back for what masking adds ({@code ‹masked›} is longer than a short secret) and the core's own fields.
 */
final class Results {

  /** Longest run of labels shown on one line of text; the structured entry has them all. */
  static final int TEXT_LABELS = 160;

  private Results() {}

  /** The bytes entries may take in one answer. */
  static long budget(Limits limits) {
    return Math.max(4_096, limits.maxResponseBytes() * 3 / 4 - 2_048);
  }

  /** What one entry adds to the answer: its JSON and its line of text. */
  static long cost(LogEntry e) {
    return utf8(e.toJson().toString()) + utf8(line(e)) + 2;
  }

  /** One entry as a line of text; a multi-line message continues on indented lines. */
  static String line(LogEntry e) {
    StringBuilder b = new StringBuilder(e.timestamp()).append(' ').append(e.severity().name());
    if (!e.labels().isEmpty()) {
      StringBuilder l = new StringBuilder();
      for (Map.Entry<String, String> label : new java.util.TreeMap<>(e.labels()).entrySet()) {
        if (!l.isEmpty()) {
          l.append(' ');
        }
        l.append(label.getKey()).append('=').append(label.getValue());
      }
      String labels = l.length() <= TEXT_LABELS ? l.toString() : l.substring(0, TEXT_LABELS - 1) + "…";
      b.append(" [").append(labels.replace('\n', ' ')).append(']');
    }
    b.append(' ').append(e.message().replace("\n", "\n  "));
    return b.toString();
  }

  /** What a search found. */
  record SearchAnswer(String connection, Settings settings, long fromNs, long toNs, List<LogEntry> entries,
      Page.Aggregate aggregate, String truncatedReason, String nextCursor, boolean gapPossible, Page.Stats stats,
      List<String> notes) {}

  /** What a tail found. */
  record TailAnswer(String connection, Settings settings, Duration waitFor, List<LogEntry> entries,
      String truncatedReason, String nextCursor, long waitedMs, boolean gapPossible, long gapBeforeNs,
      Page.Stats stats, List<String> notes) {}

  static ToolResult search(SearchAnswer a) {
    return search(a, "logs");
  }

  static ToolResult search(SearchAnswer a, String connectorType) {
    ObjectNode s = base(a.settings(), a.entries(), a.truncatedReason(), a.nextCursor(), a.gapPossible(), a.stats(),
        a.notes());
    s.put("from", instant(a.fromNs()));
    s.put("to", instant(a.toNs()));
    StringBuilder text = new StringBuilder();
    String where = a.connection() + " (" + a.settings().title() + ")";
    String range = instant(a.fromNs()) + " → " + instant(a.toNs());
    if (a.aggregate() != null) {
      s.set("aggregate", aggregate(a.aggregate()));
      int rows = a.aggregate().rows().size();
      text.append(rows).append(rows == 1 ? " row" : " rows").append(" from ").append(where).append(" · ")
          .append(range);
      table(text, a.aggregate());
    } else if (a.entries().isEmpty()) {
      text.append("No entries on ").append(where).append(" match between ").append(range).append('.');
    } else {
      int n = a.entries().size();
      text.append(n).append(n == 1 ? " entry" : " entries").append(" from ").append(where)
          .append(", newest first · ").append(range);
      for (LogEntry e : a.entries()) {
        text.append('\n').append(line(e));
      }
    }
    if (a.truncatedReason() != null) {
      text.append('\n');
      if (a.nextCursor() != null) {
        text.append("More entries are older: call ").append(LogsTools.searchName(connectorType))
            .append(" again with the same query and cursor ")
            .append(a.nextCursor());
      } else if (!a.entries().isEmpty()) {
        text.append("More entries match. Narrow the query, or search again with to=")
            .append(a.entries().getLast().timestamp()).append(" for older ones.");
      } else {
        text.append("The answer is incomplete (").append(a.truncatedReason()).append("). Narrow the range.");
      }
      if ("partial".equals(a.truncatedReason())) {
        text.append("\nThe backend stopped before it finished: these are the entries it found in time.");
      } else if ("timeout".equals(a.truncatedReason())) {
        text.append("\nThe call ran out of time: these are the entries found so far.");
      }
    }
    appendNotes(text, a.notes(), a.gapPossible(), "Entries at one moment were more than a cursor holds: some at "
        + (a.entries().isEmpty() ? "the boundary" : a.entries().getLast().timestamp()) + " may be skipped.");
    return finish(ToolResult.json(s, text.toString()), a.truncatedReason());
  }

  static ToolResult tail(TailAnswer a) {
    return tail(a, "logs");
  }

  static ToolResult tail(TailAnswer a, String connectorType) {
    ObjectNode s = base(a.settings(), a.entries(), a.truncatedReason(), a.nextCursor(), a.gapPossible(), a.stats(),
        a.notes());
    s.put("waitedMs", a.waitedMs());
    StringBuilder text = new StringBuilder();
    String where = a.connection() + " (" + a.settings().title() + ")";
    if (a.entries().isEmpty()) {
      text.append("No new entries on ").append(where).append(" in ").append(seconds(a.waitedMs()))
          .append(". Call ").append(LogsTools.tailName(connectorType)).append(" again with cursor ").append(a.nextCursor());
    } else {
      int n = a.entries().size();
      text.append(n).append(n == 1 ? " new entry" : " new entries").append(" from ").append(where)
          .append(" after ").append(seconds(a.waitedMs()));
      for (LogEntry e : a.entries()) {
        text.append('\n').append(line(e));
      }
      text.append('\n');
      if (a.truncatedReason() != null) {
        text.append("More are waiting: call ").append(LogsTools.tailName(connectorType))
            .append(" again at once with cursor ").append(a.nextCursor());
      } else {
        text.append("Next: call ").append(LogsTools.tailName(connectorType)).append(" again with cursor ").append(a.nextCursor());
      }
    }
    appendNotes(text, a.notes(), a.gapPossible(), "The cursor was more than an hour old or its boundary too full: "
        + "entries before " + instant(a.gapBeforeNs()) + " may be missing. Use " + LogsTools.searchName(connectorType)
        + " for them.");
    return finish(ToolResult.json(s, text.toString()), a.truncatedReason());
  }

  private static ObjectNode base(Settings settings, List<LogEntry> entries, String truncatedReason, String cursor,
      boolean gap, Page.Stats stats, List<String> notes) {
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    s.put("backend", settings.backend());
    ArrayNode list = s.putArray("entries");
    for (LogEntry e : entries) {
      list.add(e.toJson());
    }
    s.put("count", entries.size());
    s.put("truncated", truncatedReason != null);
    if (truncatedReason != null) {
      s.put("truncatedReason", truncatedReason);
    }
    if (cursor == null) {
      s.putNull("nextCursor");
    } else {
      s.put("nextCursor", cursor);
    }
    s.put("gapPossible", gap);
    ObjectNode st = s.putObject("stats");
    st.put("requests", stats.requests());
    if (stats.bytesScanned() >= 0) {
      st.put("bytesScanned", stats.bytesScanned());
    }
    if (stats.recordsScanned() >= 0) {
      st.put("recordsScanned", stats.recordsScanned());
    }
    if (!notes.isEmpty()) {
      ArrayNode n = s.putArray("notes");
      notes.forEach(n::add);
    }
    return s;
  }

  private static ObjectNode aggregate(Page.Aggregate a) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    ArrayNode columns = n.putArray("columns");
    a.columns().forEach(columns::add);
    n.set("rows", a.rows());
    return n;
  }

  private static void table(StringBuilder out, Page.Aggregate a) {
    int cols = a.columns().size();
    int[] width = new int[cols];
    for (int c = 0; c < cols; c++) {
      width[c] = a.columns().get(c).length();
    }
    for (JsonNode row : a.rows()) {
      for (int c = 0; c < cols; c++) {
        width[c] = Math.min(60, Math.max(width[c], cell(row.get(c)).length()));
      }
    }
    out.append('\n');
    for (int c = 0; c < cols; c++) {
      pad(out, a.columns().get(c), width[c], c == cols - 1);
    }
    for (JsonNode row : a.rows()) {
      out.append('\n');
      for (int c = 0; c < cols; c++) {
        pad(out, cell(row.get(c)), width[c], c == cols - 1);
      }
    }
  }

  private static void pad(StringBuilder out, String v, int width, boolean last) {
    String cut = v.length() <= width ? v : v.substring(0, width - 1) + "…";
    out.append(cut);
    if (!last) {
      out.append(" ".repeat(width - cut.length() + 2));
    }
  }

  private static String cell(JsonNode v) {
    return v == null || v.isNull() ? "" : v.asString().replace('\n', ' ');
  }

  private static void appendNotes(StringBuilder text, List<String> notes, boolean gap, String gapText) {
    for (String n : notes) {
      text.append("\nNote: ").append(n).append('.');
    }
    if (gap) {
      text.append("\nNote: ").append(gapText);
    }
  }

  private static ToolResult finish(ToolResult r, String truncatedReason) {
    if (truncatedReason == null) {
      return r;
    }
    return r.truncated(switch (truncatedReason) {
      case "limit" -> "row_limit";
      case "size" -> "size_limit";
      default -> truncatedReason;
    });
  }

  static String instant(long ns) {
    return Instant.ofEpochSecond(Math.floorDiv(ns, 1_000_000_000L), Math.floorMod(ns, 1_000_000_000L)).toString();
  }

  private static String seconds(long ms) {
    return ms < 1_000 ? ms + " ms" : String.format(java.util.Locale.ROOT, "%.1f s", ms / 1_000.0);
  }

  private static long utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8).length;
  }
}
