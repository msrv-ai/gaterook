package ai.msrv.gaterook.core.clients.internal;

import ai.msrv.gaterook.core.clients.ConfigEditException;
import ai.msrv.gaterook.core.clients.ConfigEditException.Reason;
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
 * <p>Removal takes the block between our markers, or, when Codex or the person rewrote the table and the markers are
 * gone, the lines from {@code [mcp_servers.<name>]} (and its sub-tables) to the next header of another table; orphaned
 * markers go too. An inline {@code mcp_servers = { … }} can't take another table: refused, with the block to paste.
 */
public final class TomlBlockEditor {

  private static final TomlMapper TOML = TomlMapper.builder().build();
  static final Pattern INLINE = Pattern.compile("(?m)^\\s*mcp_servers\\s*=\\s*\\{");
  private static final Pattern HEADER = Pattern.compile("^\\s*\\[");

  private TomlBlockEditor() {}

  /** {@code # >>> MCP Gateway · gateway-dev · managed entry: Disconnect in MCP Gateway removes it}. */
  public static String startMarker(String name) {
    return "# >>> MCP Gateway · " + name + " · managed entry: Disconnect in MCP Gateway removes it";
  }

  public static String endMarker(String name) {
    return "# <<< MCP Gateway · " + name;
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

  /** {@code text} without {@code mcp_servers.<name>}, or as it is when there is no such table (orphaned markers go). */
  public static String remove(String text, String name) {
    JsonNode before = parse(text);
    List<String> lines = splitKeepingEnds(text);
    boolean present = before.path("mcp_servers").has(name);
    int start = indexOfMarker(lines, startMarker(name), name, true);
    int end = indexOfMarker(lines, endMarker(name), name, false);
    List<String> out = new ArrayList<>(lines);
    if (start >= 0 && end > start) {
      int from = start;
      // The blank line our append put before the block goes with it.
      if (from > 0 && lines.get(from - 1).isBlank()) {
        from--;
      }
      out.subList(from, end + 1).clear();
    } else {
      // The markers are gone or orphaned: drop them and the table's own lines.
      out.removeIf(l -> isMarker(l, name));
      if (present) {
        removeTable(out, name);
      }
    }
    String result = String.join("", out);
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
      throw new ConfigEditException(Reason.INVALID, "The file isn't valid TOML" + where + ". MCP Gateway changes "
          + "nothing.", line, column, null);
    }
  }

  private static void check(String result, JsonNode expected) {
    JsonNode after;
    try {
      after = parse(result);
    } catch (ConfigEditException e) {
      throw new ConfigEditException(Reason.CHECK_FAILED, "The edited file wouldn't parse, so nothing was written.");
    }
    if (!after.equals(expected)) {
      throw new ConfigEditException(Reason.CHECK_FAILED, "The edit would change more than MCP Gateway's entry, so "
          + "nothing was written.");
    }
  }

  /** Removes {@code [mcp_servers.<name>]} and {@code [mcp_servers.<name>.*]} up to the next header of another table. */
  private static void removeTable(List<String> lines, String name) {
    Pattern own = Pattern.compile("^\\s*\\[\\s*mcp_servers\\s*\\.\\s*(?:" + Pattern.quote(name) + "|\""
        + Pattern.quote(name) + "\")\\s*(?:\\.[^\\]]*)?\\]\\s*(?:#.*)?$");
    boolean inside = false;
    for (int i = 0; i < lines.size(); ) {
      String line = stripEnd(lines.get(i));
      if (HEADER.matcher(line).find()) {
        inside = own.matcher(line).matches();
      }
      if (inside) {
        lines.remove(i);
      } else {
        i++;
      }
    }
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
