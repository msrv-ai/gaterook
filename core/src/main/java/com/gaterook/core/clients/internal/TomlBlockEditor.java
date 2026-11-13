package com.gaterook.core.clients.internal;

import com.gaterook.core.clients.ConfigEditException;
import com.gaterook.core.clients.ConfigEditException.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.toml.TomlMapper;

/**
 * Codex's {@code ~/.codex/config.toml} (report 03 §7.2): our {@code [mcp_servers.<name>]} goes in a block between
 * markers at the end of the file, written as text; Jackson's TOML module only parses, to check
 * {@code tree(after) == tree(before) + our table}. Comments and every other line stay as they are.
 *
 * <p>Removal takes our markers and the lines of {@code [mcp_servers.<name>]} and its sub-tables, each from its header
 * to the next header of another table, wherever Codex or the person moved them. A table someone put between our
 * markers (another server, a profile, a setting) stays where it is, with its keys and comments: it isn't ours, and
 * removing our entry must not take the other application's settings with it. Every edit is checked as exactly one tree
 * difference (our table) before it is returned. An inline {@code mcp_servers = { … }} can't take another table:
 * refused, with the block to paste.
 */
public final class TomlBlockEditor {

  private static final TomlMapper TOML = TomlMapper.builder().build();
  static final Pattern INLINE = Pattern.compile("(?m)^\\s*mcp_servers\\s*=\\s*\\{");
  /** One key of a table header: bare, or quoted as a basic or a literal string. */
  private static final String KEY = "(?:[^\\s.\\[\\]\"'=#,]+|\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*')";
  /**
   * A table or array-of-tables header ({@code [a.b]}, {@code [[a]]}, with an optional comment): keys joined by dots and
   * nothing else, so a line inside a multi-line array ({@code [1, 2],}) isn't taken for one.
   */
  private static final Pattern HEADER = Pattern.compile("^\\s*\\[\\[?\\s*" + KEY + "(?:\\s*\\.\\s*" + KEY
      + ")*\\s*\\]\\]?\\s*(?:#.*)?$");

  private TomlBlockEditor() {}

  /** {@code # >>> GateRook · gaterook-dev · managed entry: Disconnect in GateRook removes it}. */
  public static String startMarker(String name) {
    return "# >>> GateRook · " + name + " · managed entry: Disconnect in GateRook removes it";
  }

  public static String endMarker(String name) {
    return "# <<< GateRook · " + name;
  }

  /**
   * {@code text} with {@code block} (our markers, the table and its keys, {@code \n} line ends) appended.
   *
   * @throws ConfigEditException the file doesn't parse, {@code mcp_servers} is an inline table, or the name is taken
   */
  public static String append(String text, String name, String block) {
    JsonNode before = parse(text);
    if (INLINE.matcher(text).find()) {
      throw new ConfigEditException(Reason.INLINE_TABLE, "mcp_servers in this file is an inline table "
          + "(mcp_servers = { … }), which can't take another server. Add this entry by hand:", 0, 0, block);
    }
    if (before.path("mcp_servers").has(name)) {
      throw new ConfigEditException(Reason.UNEXPECTED_SHAPE, "The file already has mcp_servers." + name + ".");
    }
    String eol = text.contains("\r\n") ? "\r\n" : "\n";
    StringBuilder out = new StringBuilder(text);
    if (!text.isEmpty() && !text.endsWith("\n")) {
      out.append(eol);
    }
    if (!text.isBlank()) {
      out.append(eol);
    }
    out.append(block.replace("\n", eol));
    String result = out.toString();
    ObjectNode expected = (ObjectNode) before.deepCopy();
    JsonNode table = parse(block).path("mcp_servers").path(name);
    expected.withObjectProperty("mcp_servers").set(name, table);
    check(result, expected);
    return result;
  }

  /**
   * {@code text} without {@code mcp_servers.<name>}, or as it is when there is no such table (orphaned markers go).
   * The lines of any other table between our markers stay (see the class comment).
   */
  public static String remove(String text, String name) {
    JsonNode before = parse(text);
    List<String> lines = splitKeepingEnds(text);
    int start = indexOfMarker(lines, startMarker(name), name, true);
    int end = indexOfMarker(lines, endMarker(name), name, false);
    boolean[] drop = ownLines(lines, name);
    if (start > 0 && end > start && lines.get(start - 1).isBlank() && allOf(drop, start, end)) {
      // The blank line our append put before the block goes with it when nothing of someone else's stays in the block.
      drop[start - 1] = true;
    }
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < lines.size(); i++) {
      if (!drop[i]) {
        out.append(lines.get(i));
      }
    }
    String result = out.toString();
    ObjectNode expected = (ObjectNode) before.deepCopy();
    if (expected.path("mcp_servers").isObject()) {
      ((ObjectNode) expected.get("mcp_servers")).remove(name);
      if (expected.get("mcp_servers").isEmpty() && !parse(result).has("mcp_servers")) {
        // Our table was the only one: an empty mcp_servers isn't written as a header, which is the same tree to Codex.
        expected.remove("mcp_servers");
      }
    }
    check(result, expected);
    return result;
  }

  /** {@code mcp_servers.<name>} of the text, if there is one. */
  public static Optional<JsonNode> table(String text, String name) {
    JsonNode node = parse(text).path("mcp_servers").path(name);
    return node.isMissingNode() ? Optional.empty() : Optional.of(node);
  }

  /** The names under {@code mcp_servers}. */
  public static List<String> names(String text) {
    List<String> out = new ArrayList<>();
    parse(text).path("mcp_servers").propertyNames().forEach(out::add);
    return out;
  }

  /** Whether the file has our markers for {@code name}. */
  public static boolean hasMarkers(String text, String name) {
    return splitKeepingEnds(text).stream().anyMatch(l -> l.strip().equals(startMarker(name)));
  }

  static JsonNode parse(String text) {
    if (text.isBlank()) {
      return TOML.createObjectNode();
    }
    try {
      return TOML.readTree(text);
    } catch (JacksonException e) {
      TokenStreamLocation at = e.getLocation();
      int line = at == null ? 0 : at.getLineNr();
      int column = at == null ? 0 : at.getColumnNr();
      String where = line > 0 ? " (line " + line + ", column " + column + ")" : "";
      throw new ConfigEditException(Reason.INVALID, "The file isn't valid TOML" + where + ". GateRook changes "
          + "nothing.", line, column, null);
    }
  }

  /** The tree of an edited text: one that doesn't parse is a failed edit, not a file that was invalid to begin with. */
  private static JsonNode edited(String result) {
    try {
      return parse(result);
    } catch (ConfigEditException e) {
      throw new ConfigEditException(Reason.CHECK_FAILED, "The edited file wouldn't parse, so nothing was written.");
    }
  }

  private static void check(String result, JsonNode expected) {
    JsonNode after = edited(result);
    if (!after.equals(expected)) {
      throw new ConfigEditException(Reason.CHECK_FAILED, "The edit would change more than GateRook's entry, so "
          + "nothing was written.");
    }
  }

  /**
   * Which lines are ours: our markers, and each section of {@code [mcp_servers.<name>]} and its sub-tables, from the
   * header to the next header of another table, wherever it is in the file (TOML reads a key there as one of our
   * table's, even after our end marker). Everything else stays: another table between our markers, keys and comments
   * above our header, and a comment or blank line someone put after our end marker.
   */
  private static boolean[] ownLines(List<String> lines, String name) {
    Pattern own = ownHeader(name);
    boolean[] drop = new boolean[lines.size()];
    boolean inside = false;
    boolean pastEnd = false;
    for (int i = 0; i < lines.size(); i++) {
      String s = stripEnd(lines.get(i));
      if (isMarker(s, name)) {
        drop[i] = true;
        pastEnd |= s.strip().equals(endMarker(name));
        continue;
      }
      if (HEADER.matcher(s).matches()) {
        inside = own.matcher(s).matches();
        pastEnd = false;
      }
      drop[i] = inside && !(pastEnd && (s.isBlank() || s.strip().startsWith("#")));
    }
    return drop;
  }

  private static boolean allOf(boolean[] drop, int from, int to) {
    for (int i = from; i <= to; i++) {
      if (!drop[i]) {
        return false;
      }
    }
    return true;
  }

  /** The header of {@code [mcp_servers.<name>]} or of one of its sub-tables, with any quoting of the two keys. */
  private static Pattern ownHeader(String name) {
    String server = "(?:" + Pattern.quote(name) + "|\"" + Pattern.quote(name) + "\"|'" + Pattern.quote(name) + "')";
    return Pattern.compile("^\\s*\\[\\[?\\s*(?:mcp_servers|\"mcp_servers\"|'mcp_servers')\\s*\\.\\s*" + server
        + "\\s*(?:\\.[^\\]]*)?\\]\\]?\\s*(?:#.*)?$");
  }

  private static int indexOfMarker(List<String> lines, String marker, String name, boolean first) {
    int found = -1;
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).strip().equals(marker)) {
        found = i;
        if (first) {
          return i;
        }
      }
    }
    return found;
  }

  private static boolean isMarker(String line, String name) {
    String s = line.strip();
    return s.equals(startMarker(name)) || s.equals(endMarker(name));
  }

  private static String stripEnd(String line) {
    return line.replaceAll("[\\r\\n]+$", "");
  }

  /** Lines with their own {@code \n} or {@code \r\n}, so joining them gives the text back byte for byte. */
  static List<String> splitKeepingEnds(String text) {
    List<String> out = new ArrayList<>();
    Matcher m = Pattern.compile("[^\\n]*\\n|[^\\n]+$").matcher(text);
    while (m.find()) {
      out.add(m.group());
    }
    return out;
  }

  // ── Writing values ──────────────────────────────────────────────────────────────────────────────────────────

  /** A TOML basic string: {@code \} and {@code "} escaped, control characters as {@code \\uXXXX}. */
  public static String basic(String value) {
    StringBuilder b = new StringBuilder("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '\\' -> b.append("\\\\");
        case '"' -> b.append("\\\"");
        case '\n' -> b.append("\\n");
        case '\t' -> b.append("\\t");
        case '\r' -> b.append("\\r");
        default -> {
          if (c < 0x20 || c == 0x7f) {
            b.append(String.format("\\u%04X", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.append('"').toString();
  }

  /** A TOML literal string ({@code '…'}, no escapes) when the value allows it, else a basic string. */
  public static String literalOrBasic(String value) {
    if (value.indexOf('\'') < 0 && value.chars().noneMatch(c -> c < 0x20 || c == 0x7f)) {
      return "'" + value + "'";
    }
    return basic(value);
  }
}
