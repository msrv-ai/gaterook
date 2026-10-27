package ai.msrv.gaterook.core.invocation;

import ai.msrv.gaterook.connector.api.Limits;
import ai.msrv.gaterook.connector.api.ToolResult;
import ai.msrv.gaterook.core.platform.CanonicalJson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shapes a masked result for the agent (ARCHITECTURE.md §5 step 10): a table becomes the {@code structuredContent}
 * of its tool's output schema plus a text rendering (the core renders tables, connectors don't), and the whole result
 * is cut to the call's limits — whole rows of a table, whole entries of the largest array of a JSON result, text at a
 * character boundary — with {@code truncated} and the reason ({@code row_limit}, {@code size_limit}). The size counted
 * is what the agent receives: text plus structured content.
 */
public final class ResultLimiter {

  /** Cells longer than this are cut in the text rendering; the structured rows keep up to {@code maxCellChars}. */
  static final int TEXT_CELL = 200;

  private ResultLimiter() {}

  /**
   * @param elapsedMs the resource time, reported in a table's first line and its {@code elapsedMs}
   */
  public static ToolResult limit(ToolResult masked, Limits limits, long elapsedMs) {
    long max = Math.max(1_024, limits.maxResponseBytes());
    if (masked.table() != null) {
      return table(masked, limits, max, elapsedMs);
    }
    if (size(masked) <= max) {
      return masked;
    }
    ToolResult cut = masked;
    if (masked.structuredContent() != null && masked.structuredContent().isObject()) {
      cut = cutLargestArray(masked, max);
    }
    if (size(cut) > max) {
      cut = cutText(cut, max);
    }
    return cut.truncated() ? cut : cut.truncated("size_limit");
  }

  /** Bytes of what the agent gets: every text and URI, and the structured content as JSON. */
  public static long size(ToolResult r) {
    long n = 0;
    for (ToolResult.Content c : r.content()) {
      n += utf8(c.text()) + utf8(c.uri()) + utf8(c.name()) + utf8(c.mimeType());
    }
    if (r.structuredContent() != null) {
      n += CanonicalJson.bytes(r.structuredContent()).length;
    }
    return n;
  }

  /** SHA-256 hex of the result as the agent gets it (audit {@code result_sha256}, approvals' executed hash). */
  public static String sha256(ToolResult r) {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    ArrayNode content = n.putArray("content");
    for (ToolResult.Content c : r.content()) {
      ObjectNode o = content.addObject();
      o.put("type", c.type());
      o.put("text", c.text());
      o.put("uri", c.uri());
    }
    n.set("structuredContent", r.structuredContent());
    n.put("isError", r.isError());
    return CanonicalJson.sha256Hex(n);
  }

  /** The first characters of the result for the audit's preview. */
  public static String preview(ToolResult r, int chars) {
    StringBuilder out = new StringBuilder();
    for (ToolResult.Content c : r.content()) {
      if (c.text() != null) {
        out.append(c.text()).append('\n');
      } else if (c.uri() != null) {
        out.append(c.uri()).append('\n');
      }
      if (out.length() >= chars) {
        break;
      }
    }
    if (out.isEmpty() && r.structuredContent() != null) {
      out.append(CanonicalJson.text(r.structuredContent()));
    }
    String text = out.toString().strip();
    return text.length() <= chars ? text : text.substring(0, Character.isHighSurrogate(text.charAt(chars - 1))
        ? chars - 1 : chars);
  }

  // ── Tables ──────────────────────────────────────────────────────────────────────────────────────────────────

  private static ToolResult table(ToolResult r, Limits limits, long max, long elapsedMs) {
    ToolResult.Table t = r.table();
    int total = t.rows().size();
    int keep = Math.min(total, Math.max(0, limits.maxRows()));
    String reason = r.truncated() ? r.truncatedReason() : keep < total ? "row_limit" : null;
    ToolResult shaped = tableResult(r, keep, reason, elapsedMs);
    if (size(shaped) > max) {
      int lo = 0;
      int hi = keep;
      while (lo < hi) {
        int mid = (lo + hi + 1) >>> 1;
        if (size(tableResult(r, mid, "size_limit", elapsedMs)) <= max) {
          lo = mid;
        } else {
          hi = mid - 1;
        }
      }
      shaped = tableResult(r, lo, "size_limit", elapsedMs);
    }
    return shaped;
  }

  /** The result with the first {@code rows} rows: structured content of the output schema and its text. */
  private static ToolResult tableResult(ToolResult r, int rows, String reason, long elapsedMs) {
    ToolResult.Table t = r.table();
    ArrayNode kept = JsonNodeFactory.instance.arrayNode();
    for (int i = 0; i < rows; i++) {
      kept.add(t.rows().get(i));
    }
    ToolResult.Table cutTable = new ToolResult.Table(t.columns(), kept, t.nextCursor());
    ObjectNode s = JsonNodeFactory.instance.objectNode();
    if (r.structuredContent() != null && r.structuredContent().isObject()) {
      // The connector's own facts stay; what the core computes below wins.
      s.setAll((ObjectNode) r.structuredContent().deepCopy());
    }
    ArrayNode columns = s.putArray("columns");
    ArrayNode types = s.putArray("types");
    for (ToolResult.Column c : t.columns()) {
      columns.add(c.name());
      types.add(c.type() == null ? "" : c.type());
    }
    s.set("rows", kept);
    s.put("rowCount", rows);
    boolean truncated = reason != null;
    s.put("truncated", truncated);
    if (truncated) {
      s.put("truncatedReason", reason);
    } else {
      s.remove("truncatedReason");
    }
    s.put("elapsedMs", elapsedMs);
    if (!s.has("masked")) {
      s.putArray("masked");
    }
    if (t.nextCursor() != null) {
      s.put("nextCursor", t.nextCursor());
    }
    List<ToolResult.Content> content = new ArrayList<>();
    content.add(ToolResult.Content.text(text(cutTable, t.rows().size(), truncated, reason, elapsedMs)));
    for (ToolResult.Content c : r.content()) {
      content.add(c);
    }
    ToolResult out = new ToolResult(content, s, r.isError(), cutTable, truncated, reason, r.maskPaths(),
        r.maskedCount(), 0, r.meta());
    return out;
  }

  /** {@code 12 rows · 38 ms}, then the columns padded to their widest cell. */
  static String text(ToolResult.Table t, int totalRows, boolean truncated, String reason, long elapsedMs) {
    StringBuilder out = new StringBuilder();
    int n = t.rows().size();
    out.append(n).append(n == 1 ? " row" : " rows");
    if (truncated) {
      out.append(" of ").append(totalRows > n ? Integer.toString(totalRows) : "more").append(", cut by ")
          .append("row_limit".equals(reason) ? "the row limit" : "size_limit".equals(reason) ? "the size limit"
              : reason);
    }
    out.append(" · ").append(elapsedMs).append(" ms");
    int cols = t.columns().size();
    if (cols == 0) {
      return out.toString();
    }
    String[][] cells = new String[n + 1][cols];
    int[] width = new int[cols];
    for (int c = 0; c < cols; c++) {
      cells[0][c] = cell(t.columns().get(c).name());
      width[c] = cells[0][c].length();
    }
    for (int i = 0; i < n; i++) {
      JsonNode row = t.rows().get(i);
      for (int c = 0; c < cols; c++) {
        JsonNode v = row.isArray() ? row.get(c) : row.get(t.columns().get(c).name());
        cells[i + 1][c] = cell(v == null || v.isNull() ? "NULL" : v.isString() ? v.asString() : v.toString());
        width[c] = Math.max(width[c], cells[i + 1][c].length());
      }
    }
    for (String[] line : cells) {
      out.append('\n');
      for (int c = 0; c < cols; c++) {
        out.append(line[c]);
        if (c < cols - 1) {
          out.append(" ".repeat(width[c] - line[c].length() + 2));
        }
      }
    }
    if (t.nextCursor() != null) {
      out.append("\nMore rows: call again with cursor ").append(t.nextCursor());
    }
    return out.toString();
  }

  private static String cell(String v) {
    String flat = v.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ');
    return flat.length() <= TEXT_CELL ? flat : flat.substring(0, TEXT_CELL - 1) + "…";
  }

  // ── JSON and text ───────────────────────────────────────────────────────────────────────────────────────────

  /** Drops whole entries from the end of the largest array of the structured content until it fits. */
  private static ToolResult cutLargestArray(ToolResult r, long max) {
    ObjectNode s = (ObjectNode) r.structuredContent().deepCopy();
    String largest = null;
    long largestBytes = -1;
    for (Map.Entry<String, JsonNode> e : s.properties()) {
      if (e.getValue().isArray()) {
        long b = CanonicalJson.bytes(e.getValue()).length;
        if (b > largestBytes) {
          largest = e.getKey();
          largestBytes = b;
        }
      }
    }
    if (largest == null) {
      return r;
    }
    ArrayNode all = (ArrayNode) s.get(largest);
    long rest = size(new ToolResult(r.content(), JsonNodeFactory.instance.objectNode(), r.isError(), null, false,
        null, List.of(), 0, 0, null)) + CanonicalJson.bytes(s).length - largestBytes;
    int lo = 0;
    int hi = all.size();
    while (lo < hi) {
      int mid = (lo + hi + 1) >>> 1;
      long bytes = rest + CanonicalJson.bytes(slice(all, mid)).length;
      if (bytes <= max) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }
    s.set(largest, slice(all, lo));
    s.put("truncated", true);
    return new ToolResult(r.content(), s, r.isError(), null, true, "size_limit", r.maskPaths(), r.maskedCount(), 0,
        r.meta());
  }

  private static ArrayNode slice(ArrayNode all, int n) {
    ArrayNode out = JsonNodeFactory.instance.arrayNode();
    for (int i = 0; i < n; i++) {
      out.add(all.get(i));
    }
    return out;
  }

  /** Cuts texts from the last one back, at a character boundary, with a note; structured content is kept. */
  private static ToolResult cutText(ToolResult r, long max) {
    long structured = r.structuredContent() == null ? 0 : CanonicalJson.bytes(r.structuredContent()).length;
    long budget = Math.max(0, max - structured);
    List<ToolResult.Content> out = new ArrayList<>();
    String note = "\n… cut by the size limit";
    for (ToolResult.Content c : r.content()) {
      long cost = utf8(c.text()) + utf8(c.uri()) + utf8(c.name()) + utf8(c.mimeType());
      if (cost <= budget) {
        out.add(c);
        budget -= cost;
        continue;
      }
      long noteBytes = utf8(note);
      if (c.text() != null && budget > noteBytes + 16) {
        out.add(ToolResult.Content.text(prefix(c.text(), budget - noteBytes) + note));
      }
      budget = 0;
    }
    if (out.isEmpty()) {
      out.add(ToolResult.Content.text("The result was larger than the size limit and was left out."));
    }
    return new ToolResult(out, structured > max ? null : r.structuredContent(), r.isError(), null, true,
        "size_limit", r.maskPaths(), r.maskedCount(), 0, r.meta());
  }

  /** The longest prefix of {@code s} whose UTF-8 form has at most {@code bytes} bytes. */
  static String prefix(String s, long bytes) {
    long n = 0;
    int i = 0;
    while (i < s.length()) {
      int cp = s.codePointAt(i);
      int len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
      if (n + len > bytes) {
        break;
      }
      n += len;
      i += Character.charCount(cp);
    }
    return s.substring(0, i);
  }

  private static long utf8(String s) {
    return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
  }
}
