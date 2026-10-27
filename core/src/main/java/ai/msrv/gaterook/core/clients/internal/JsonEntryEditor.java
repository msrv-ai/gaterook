package ai.msrv.gaterook.core.clients.internal;

import ai.msrv.gaterook.core.clients.ConfigEditException;
import ai.msrv.gaterook.core.clients.ConfigEditException.Reason;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.TokenStreamLocation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Surgical edits of a client's JSON config (report 03 §7.1, ARCHITECTURE.md §7.2 "Writing configuration files"): one entry under
 * {@code mcpServers} is inserted, replaced or removed at its position in the text, and every other byte stays as it
 * was. Each edit checks {@code tree(after) == tree(before) ± our entry}; if that doesn't hold nothing is returned.
 *
 * <p>Only strict JSON: comments, trailing commas and duplicate keys are refused (Claude Desktop and the others read
 * strict JSON, and re-serializing would reformat entries that aren't ours). The layout of the file — indentation unit,
 * line endings, a byte order mark, a final newline, a minified file — is kept; our entry follows it.
 */
public final class JsonEntryEditor {

  private static final JsonMapper STRICT = JsonMapper.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
      .build();
  private static final char BOM = '﻿';

  private JsonEntryEditor() {}

  /** One member of an object: where its key starts and where its value ends (char offsets). */
  record Member(String name, int keyStart, int valueStart, int valueEnd) {}

  /** An object's braces and members. */
  record Obj(int open, int close, List<Member> members) {}

  /**
   * The text with {@code entry} as {@code rootKey.name}: replaced in place when the name exists, else appended as the
   * object's last member (the object and the file are created when missing).
   */
  public static String upsert(String text, String rootKey, String name, ObjectNode entry) {
    boolean bom = !text.isEmpty() && text.charAt(0) == BOM;
    String body = bom ? text.substring(1) : text;
    Layout lay = Layout.detect(body);
    String out;
    ObjectNode expected;
    if (body.isBlank()) {
      ObjectNode servers = STRICT.createObjectNode();
      servers.set(name, entry);
      expected = STRICT.createObjectNode();
      expected.set(rootKey, servers);
      out = render(expected, "", lay) + lay.eol();
    } else {
      JsonNode before = parse(body);
      if (!before.isObject()) {
        throw new ConfigEditException(Reason.UNEXPECTED_SHAPE, "The file isn't a JSON object, so GateRook can't "
            + "add an entry to it.");
      }
      Obj top = locate(body, null);
      Member root = top.members().stream().filter(m -> m.name().equals(rootKey)).reduce((a, b) -> b).orElse(null);
      expected = (ObjectNode) before.deepCopy();
      if (root != null && !before.get(rootKey).isObject()) {
        throw new ConfigEditException(Reason.UNEXPECTED_SHAPE, "\"" + rootKey + "\" isn't an object, so GateRook "
            + "can't add an entry to it.");
      }
      ObjectNode servers = root == null ? STRICT.createObjectNode() : (ObjectNode) expected.get(rootKey);
      servers.set(name, entry);
      expected.set(rootKey, servers);
      if (root == null) {
        ObjectNode wrapper = STRICT.createObjectNode();
        wrapper.set(name, entry);
        out = insert(body, top, rootKey, wrapper, lay);
      } else {
        Obj object = locate(body, rootKey);
        Member existing = object.members().stream().filter(m -> m.name().equals(name)).findFirst().orElse(null);
        if (existing != null) {
          String indent = indentOf(body, existing.keyStart());
          String member = inline(body, object) ? compactMember(name, entry) : member(name, entry, indent, lay);
          out = body.substring(0, existing.keyStart()) + member + body.substring(existing.valueEnd());
        } else {
          out = insert(body, object, name, entry, lay);
        }
      }
    }
    check(out, expected);
    return bom ? BOM + out : out;
  }

  /** The text without {@code rootKey.name}; the text as it is when there is no such entry. */
  public static String remove(String text, String rootKey, String name) {
    boolean bom = !text.isEmpty() && text.charAt(0) == BOM;
    String body = bom ? text.substring(1) : text;
    if (body.isBlank()) {
      return text;
    }
    JsonNode before = parse(body);
    if (!before.isObject() || !before.path(rootKey).isObject() || !before.path(rootKey).has(name)) {
      return text;
    }
    Obj object = locate(body, rootKey);
    List<Member> members = object.members();
    int i = indexOf(members, name);
    String out;
    if (members.size() == 1) {
      out = body.substring(0, object.open() + 1) + body.substring(object.close());
    } else if (i == members.size() - 1) {
      out = body.substring(0, members.get(i - 1).valueEnd()) + body.substring(members.get(i).valueEnd());
    } else {
      out = body.substring(0, members.get(i).keyStart()) + body.substring(members.get(i + 1).keyStart());
    }
    ObjectNode expected = (ObjectNode) before.deepCopy();
    ((ObjectNode) expected.get(rootKey)).remove(name);
    check(out, expected);
    return bom ? BOM + out : out;
  }

  /** {@code rootKey.name} of the text, if there is one. */
  public static Optional<JsonNode> entry(String text, String rootKey, String name) {
    String body = !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    if (body.isBlank()) {
      return Optional.empty();
    }
    JsonNode node = parse(body).path(rootKey).path(name);
    return node.isMissingNode() ? Optional.empty() : Optional.of(node);
  }

  /** The names under {@code rootKey}. */
  public static Set<String> names(String text, String rootKey) {
    String body = !text.isEmpty() && text.charAt(0) == BOM ? text.substring(1) : text;
    Set<String> out = new TreeSet<>();
    if (!body.isBlank()) {
      parse(body).path(rootKey).propertyNames().forEach(out::add);
    }
    return out;
  }

  /** Parses strict JSON, refusing JSONC and duplicate keys with the position of the problem. */
  static JsonNode parse(String body) {
    try {
      return STRICT.readTree(body);
    } catch (JacksonException e) {
      TokenStreamLocation at = e.getLocation();
      int line = at == null ? 0 : at.getLineNr();
      int column = at == null ? 0 : at.getColumnNr();
      String detail = e.getOriginalMessage();
      boolean jsonc = detail != null && (detail.contains("'/'") || detail.contains("'#'")
          || detail.contains("trailing comma") || detail.contains("expected double-quote to start field name"));
      String where = line > 0 ? " (line " + line + ", column " + column + ")" : "";
      if (jsonc) {
        throw new ConfigEditException(Reason.JSONC, "The file has comments or trailing commas" + where + ". "
            + "GateRook writes only strict JSON, so it changes nothing; add the entry by hand.", line, column, null);
      }
      throw new ConfigEditException(Reason.INVALID, "The file isn't valid JSON" + where + ": " + firstLine(
          tokensMasked(detail)) + ". GateRook changes nothing.", line, column, null);
    }
  }

  private static final java.util.regex.Pattern TOKEN = java.util.regex.Pattern.compile("gw[tbor]_[A-Za-z0-9_-]+");

  /**
   * Jackson quotes the text it couldn't read ({@code Unrecognized token 'gwt_…'}): a token of the file shows as its
   * prefix, {@code gwt_K9xP…}, as everywhere else (§9 "token shown once").
   */
  static String tokensMasked(String text) {
    return text == null ? null : TOKEN.matcher(text).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
        m.group().length() > 8 ? m.group().substring(0, 8) + "…" : m.group()));
  }

  /** The members of the top object, or of its member {@code child} (an object). */
  static Obj locate(String body, String child) {
    try (JsonParser p = STRICT.createParser(body)) {
      if (p.nextToken() != JsonToken.START_OBJECT) {
        throw new ConfigEditException(Reason.UNEXPECTED_SHAPE, "The file isn't a JSON object.");
      }
      Obj[] found = new Obj[1];
      Obj top = readObject(p, child, found);
      if (child == null) {
        return top;
      }
      if (found[0] == null) {
        throw new ConfigEditException(Reason.UNEXPECTED_SHAPE, "\"" + child + "\" isn't an object.");
      }
      return found[0];
    }
  }

  /** Reads an object whose START_OBJECT is the current token; descends into member {@code child} once. */
  private static Obj readObject(JsonParser p, String child, Obj[] found) {
    int open = offset(p.currentTokenLocation());
    List<Member> members = new ArrayList<>();
    while (p.nextToken() == JsonToken.PROPERTY_NAME) {
      int keyStart = offset(p.currentTokenLocation());
      String name = p.currentName();
      JsonToken value = p.nextToken();
      int valueStart = offset(p.currentTokenLocation());
      if (child != null && name.equals(child) && value == JsonToken.START_OBJECT) {
        found[0] = readObject(p, null, new Obj[1]);
      } else if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
        p.skipChildren();
      } else if (value == JsonToken.VALUE_STRING) {
        // A string token is read lazily: finish it, so the location is after its closing quote.
        p.getString();
      }
      members.add(new Member(name, keyStart, valueStart, offset(p.currentLocation())));
    }
    return new Obj(open, offset(p.currentTokenLocation()), List.copyOf(members));
  }

  private static int offset(TokenStreamLocation at) {
    return (int) at.getCharOffset();
  }

  /** Appends {@code name: value} as the last member of {@code object}. */
  private static String insert(String body, Obj object, String name, JsonNode value, Layout lay) {
    List<Member> members = object.members();
    if (members.isEmpty()) {
      String closeIndent = indentOf(body, object.open());
      String inner = closeIndent + lay.unit();
      String content = lay.eol() + inner + member(name, value, inner, lay) + lay.eol() + closeIndent;
      return body.substring(0, object.open() + 1) + content + body.substring(object.close());
    }
    Member last = members.getLast();
    String indent = indentOf(body, last.keyStart());
    String insertion = inline(body, object) ? ", " + compactMember(name, value)
        : "," + lay.eol() + indent + member(name, value, indent, lay);
    return body.substring(0, last.valueEnd()) + insertion + body.substring(last.valueEnd());
  }

  /** A minified object (its first member on the line of its brace): our member goes in compact. */
  private static boolean inline(String body, Obj object) {
    if (object.members().isEmpty()) {
      return false;
    }
    String between = body.substring(object.open() + 1, object.members().getFirst().keyStart());
    return between.indexOf('\n') < 0;
  }

  /** The whitespace that starts the line holding {@code pos}. */
  private static String indentOf(String body, int pos) {
    int start = body.lastIndexOf('\n', pos - 1) + 1;
    int end = start;
    while (end < body.length() && (body.charAt(end) == ' ' || body.charAt(end) == '\t')) {
      end++;
    }
    return body.substring(start, Math.min(end, pos));
  }

  private static int indexOf(List<Member> members, String name) {
    for (int i = 0; i < members.size(); i++) {
      if (members.get(i).name().equals(name)) {
        return i;
      }
    }
    return -1;
  }

  private static void check(String out, JsonNode expected) {
    JsonNode after;
    try {
      after = STRICT.readTree(out);
    } catch (JacksonException e) {
      throw new ConfigEditException(Reason.CHECK_FAILED, "The edited file wouldn't parse, so nothing was written.");
    }
    if (!after.equals(expected)) {
      throw new ConfigEditException(Reason.CHECK_FAILED, "The edit would change more than GateRook's entry, so "
          + "nothing was written.");
    }
  }

  // ── Rendering our entry ─────────────────────────────────────────────────────────────────────────────────────

  /** {@code "name": value} at {@code indent}, multi-line like the rest of the file. */
  static String member(String name, JsonNode value, String indent, Layout lay) {
    return quote(name) + ": " + render(value, indent, lay);
  }

  /** The whole entry as one value, for the "add it by hand" fragment and the Add to Cursor link. */
  public static String pretty(String name, JsonNode value) {
    Layout lay = new Layout("  ", "\n");
    return member(name, value, "", lay);
  }

  private static String compactMember(String name, JsonNode value) {
    return quote(name) + ": " + STRICT.writeValueAsString(value);
  }

  /** Objects one member per line; arrays of scalars on one line ({@code "args": ["stdio", "--endpoint", "dev"]}). */
  private static String render(JsonNode node, String indent, Layout lay) {
    if (node.isObject()) {
      if (node.isEmpty()) {
        return "{}";
      }
      StringBuilder b = new StringBuilder("{");
      Iterator<Map.Entry<String, JsonNode>> it = node.properties().iterator();
      String inner = indent + lay.unit();
      while (it.hasNext()) {
        Map.Entry<String, JsonNode> e = it.next();
        b.append(lay.eol()).append(inner).append(quote(e.getKey())).append(": ").append(render(e.getValue(), inner,
            lay));
        if (it.hasNext()) {
          b.append(',');
        }
      }
      return b.append(lay.eol()).append(indent).append('}').toString();
    }
    if (node.isArray()) {
      boolean scalars = true;
      for (JsonNode v : node) {
        scalars &= v.isValueNode();
      }
      if (scalars) {
        List<String> items = new ArrayList<>();
        node.forEach(v -> items.add(STRICT.writeValueAsString(v)));
        return "[" + String.join(", ", items) + "]";
      }
      StringBuilder b = new StringBuilder("[");
      String inner = indent + lay.unit();
      for (int i = 0; i < node.size(); i++) {
        b.append(lay.eol()).append(inner).append(render(node.get(i), inner, lay));
        if (i < node.size() - 1) {
          b.append(',');
        }
      }
      return b.append(lay.eol()).append(indent).append(']').toString();
    }
    return STRICT.writeValueAsString(node);
  }

  private static String quote(String s) {
    return STRICT.writeValueAsString(STRICT.getNodeFactory().stringNode(s));
  }

  private static String firstLine(String s) {
    if (s == null) {
      return "unexpected content";
    }
    int nl = s.indexOf('\n');
    return nl < 0 ? s : s.substring(0, nl);
  }

  /** Indentation unit and line ending of a file. */
  record Layout(String unit, String eol) {

    /** The first indented line sets the unit (two spaces when nothing is indented); CRLF wins if the file uses it. */
    static Layout detect(String text) {
      String eol = text.contains("\r\n") ? "\r\n" : "\n";
      String unit = "  ";
      for (String line : text.split("\n", -1)) {
        int n = 0;
        while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) {
          n++;
        }
        if (n > 0 && n < line.length() && line.charAt(n) != '\r') {
          unit = line.substring(0, n);
          break;
        }
      }
      return new Layout(unit, eol);
    }
  }
}
