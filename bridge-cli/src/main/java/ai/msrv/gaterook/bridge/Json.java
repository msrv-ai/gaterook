package ai.msrv.gaterook.bridge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON for the bridge (JDK only, no reflection: it runs as a small native executable): parse into
 * {@code Map}, {@code List}, {@code String}, {@code Double}/{@code Long}, {@code Boolean} and {@code null}; write
 * strings and flat objects. Messages are passed on as the client sent them; only their {@code id}, {@code method} and
 * {@code params} are read.
 */
final class Json {

  private final String s;
  private int i;

  private Json(String s) {
    this.s = s;
  }

  /** Parses one JSON value; trailing content is an error. */
  static Object parse(String text) {
    Json p = new Json(text);
    p.ws();
    Object v = p.value(0);
    p.ws();
    if (p.i != p.s.length()) {
      throw new IllegalArgumentException("trailing content at " + p.i);
    }
    return v;
  }

  private Object value(int depth) {
    if (depth > 64) {
      throw new IllegalArgumentException("too deep");
    }
    if (i >= s.length()) {
      throw new IllegalArgumentException("unexpected end");
    }
    char c = s.charAt(i);
    return switch (c) {
      case '{' -> object(depth);
      case '[' -> array(depth);
      case '"' -> string();
      case 't' -> literal("true", Boolean.TRUE);
      case 'f' -> literal("false", Boolean.FALSE);
      case 'n' -> literal("null", null);
      default -> number();
    };
  }

  private Map<String, Object> object(int depth) {
    Map<String, Object> out = new LinkedHashMap<>();
    i++;
    ws();
    if (peek('}')) {
      i++;
      return out;
    }
    while (true) {
      ws();
      if (!peek('"')) {
        throw new IllegalArgumentException("expected a name at " + i);
      }
      String key = string();
      ws();
      expect(':');
      ws();
      out.put(key, value(depth + 1));
      ws();
      if (peek(',')) {
        i++;
        continue;
      }
      expect('}');
      return out;
    }
  }

  private List<Object> array(int depth) {
    List<Object> out = new ArrayList<>();
    i++;
    ws();
    if (peek(']')) {
      i++;
      return out;
    }
    while (true) {
      ws();
      out.add(value(depth + 1));
      ws();
      if (peek(',')) {
        i++;
        continue;
      }
      expect(']');
      return out;
    }
  }

  private String string() {
    expect('"');
    StringBuilder b = new StringBuilder();
    while (true) {
      if (i >= s.length()) {
        throw new IllegalArgumentException("unterminated string");
      }
      char c = s.charAt(i++);
      if (c == '"') {
        return b.toString();
      }
      if (c == '\\') {
        char e = s.charAt(i++);
        switch (e) {
          case '"', '\\', '/' -> b.append(e);
          case 'b' -> b.append('\b');
          case 'f' -> b.append('\f');
          case 'n' -> b.append('\n');
          case 'r' -> b.append('\r');
          case 't' -> b.append('\t');
          case 'u' -> {
            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
            i += 4;
          }
          default -> throw new IllegalArgumentException("bad escape at " + i);
        }
      } else if (c < 0x20) {
        throw new IllegalArgumentException("control character in string at " + i);
      } else {
        b.append(c);
      }
    }
  }

  private Object number() {
    int start = i;
    while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
      i++;
    }
    String n = s.substring(start, i);
    if (n.isEmpty()) {
      throw new IllegalArgumentException("unexpected character at " + start);
    }
    if (n.matches("-?\\d{1,18}")) {
      return Long.parseLong(n);
    }
    return Double.parseDouble(n);
  }

  private Object literal(String word, Object v) {
    if (!s.startsWith(word, i)) {
      throw new IllegalArgumentException("unexpected token at " + i);
    }
    i += word.length();
    return v;
  }

  private void ws() {
    while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
      i++;
    }
  }

  private boolean peek(char c) {
    return i < s.length() && s.charAt(i) == c;
  }

  private void expect(char c) {
    if (!peek(c)) {
      throw new IllegalArgumentException("expected '" + c + "' at " + i);
    }
    i++;
  }

  /** A JSON string literal. */
  static String quote(String v) {
    StringBuilder b = new StringBuilder("\"");
    for (int k = 0; k < v.length(); k++) {
      char c = v.charAt(k);
      switch (c) {
        case '"' -> b.append("\\\"");
        case '\\' -> b.append("\\\\");
        case '\n' -> b.append("\\n");
        case '\r' -> b.append("\\r");
        case '\t' -> b.append("\\t");
        default -> {
          if (c < 0x20) {
            b.append(String.format("\\u%04x", (int) c));
          } else {
            b.append(c);
          }
        }
      }
    }
    return b.append('"').toString();
  }

  /** Writes a value back: strings, numbers, booleans, null, maps and lists of them. */
  static String write(Object v) {
    if (v == null) {
      return "null";
    }
    if (v instanceof String str) {
      return quote(str);
    }
    if (v instanceof Boolean || v instanceof Long || v instanceof Integer) {
      return v.toString();
    }
    if (v instanceof Double d) {
      return d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15 ? Long.toString(d.longValue())
          : d.toString();
    }
    if (v instanceof Map<?, ?> m) {
      StringBuilder b = new StringBuilder("{");
      boolean first = true;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (!first) {
          b.append(',');
        }
        first = false;
        b.append(quote(String.valueOf(e.getKey()))).append(':').append(write(e.getValue()));
      }
      return b.append('}').toString();
    }
    if (v instanceof List<?> l) {
      StringBuilder b = new StringBuilder("[");
      for (int k = 0; k < l.size(); k++) {
        if (k > 0) {
          b.append(',');
        }
        b.append(write(l.get(k)));
      }
      return b.append(']').toString();
    }
    throw new IllegalArgumentException("can't write " + v.getClass());
  }

  /** {@code map.get(key)} as a string, or {@code null}. */
  static String string(Object map, String key) {
    return map instanceof Map<?, ?> m && m.get(key) instanceof String v ? v : null;
  }

  static Object get(Object map, String key) {
    return map instanceof Map<?, ?> m ? m.get(key) : null;
  }
}
