package ai.msrv.gaterook.core.platform;

import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The secret values one call has seen (ARCHITECTURE.md §9 "Agents never see secrets", report 12 §7.7): every
 * value the {@code ScopedSecretResolver} handed out and every credential the connector derived itself
 * ({@code ExecContext.taint}: GCP and STS tokens, Vault tokens, OAuth tokens). The {@link Masker} replaces each of
 * them, and their usual encodings, by {@code ‹masked›} before anything leaves the call: the result, the audit row,
 * Playground, notifications and logs.
 *
 * <p>Forms kept per value: raw, Base64 and Base64url (without padding, so padded echoes match too), URL-encoded the
 * ways common libraries do it ({@link URLEncoder} with {@code +} or {@code %20} for a space, RFC 3986 that keeps only
 * {@code A-Za-z0-9-._~}, JavaScript's {@code encodeURIComponent} that also keeps {@code !*'()}), JSON-escaped the ways
 * common serializers do it ({@link #JSON_STYLES}: minimal, <code>&#92;uXXXX</code> for non-ASCII in either case,
 * <code>&#92;/</code>, HTML-safe <code>&#92;u003c</code>), and Base64 of {@code user:password} for logins; binary
 * values without a text form ({@link #addBytes}): Base64, Base64url and hex. Identical forms are kept once, so a value
 * without special characters costs the masker one form, not a dozen. Forms shorter than {@value #MIN_LENGTH}
 * characters are ignored: masking every {@code "on"} of a result would destroy it. Partial or hashed echoes are not
 * caught (report 12 §9, residual risk).
 *
 * <p>Values are held as {@code char[]} and zeroed by {@link #clear()} when the call ends. Thread-safe: a connector may
 * add values from several threads of one call.
 */
public final class Taint {

  /** Shorter forms are not tracked. */
  public static final int MIN_LENGTH = 4;

  /** How one JSON serializer escapes a string between its quotes. */
  record JsonStyle(String name, boolean asciiOnly, boolean upperHex, boolean escapeSlash, String htmlChars) {}

  /** The escapings of the JSON a connector may echo: what services written in common languages emit by default. */
  static final List<JsonStyle> JSON_STYLES = List.of(
      new JsonStyle("minimal (Jackson, JSON.stringify)", false, true, false, ""),
      new JsonStyle("ensure_ascii (Python json.dumps)", true, false, false, ""),
      new JsonStyle("ESCAPE_NON_ASCII (Jackson)", true, true, false, ""),
      new JsonStyle("escaped slash", false, true, true, ""),
      new JsonStyle("json_encode (PHP)", true, false, true, ""),
      new JsonStyle("HTML-safe (Go encoding/json)", false, false, false, "<>&\u2028\u2029"),
      new JsonStyle("HTML-safe (Gson)", false, false, false, "<>&='"));

  /** Characters RFC 3986 never percent-encodes ("unreserved"), besides letters and digits. */
  private static final String RFC_3986_KEPT = "-._~";
  /** What JavaScript's {@code encodeURIComponent} keeps besides letters and digits. */
  private static final String URI_COMPONENT_KEPT = "-._~!*'()";
  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  private final List<char[]> forms = new ArrayList<>();
  /** The forms longest first and indexed by their first character; replaced whole, so readers see one snapshot. */
  private volatile Index index = Index.EMPTY;

  /** Adds one value and its encodings. */
  public synchronized void add(CharSequence value) {
    if (value == null || value.length() < MIN_LENGTH) {
      return;
    }
    char[] raw = toChars(value);
    addForm(raw);
    byte[] utf8 = utf8(raw);
    try {
      addForm(Base64.getEncoder().withoutPadding().encodeToString(utf8).toCharArray());
      addForm(Base64.getUrlEncoder().withoutPadding().encodeToString(utf8).toCharArray());
      addForm(percentEncoded(utf8, RFC_3986_KEPT, "%20"));
      addForm(percentEncoded(utf8, RFC_3986_KEPT, "+"));
      addForm(percentEncoded(utf8, URI_COMPONENT_KEPT, "%20"));
    } finally {
      Arrays.fill(utf8, (byte) 0);
    }
    String text = new String(raw);
    String form = URLEncoder.encode(text, StandardCharsets.UTF_8);
    addForm(form.toCharArray());
    addForm(form.replace("+", "%20").toCharArray());
    for (JsonStyle style : JSON_STYLES) {
      addForm(jsonEscaped(text, style).toCharArray());
    }
    rebuild();
  }

  /**
   * Adds a binary value that has no text form (a keystore, a keytab, a raw key): a connector can only echo it
   * encoded, so its Base64, Base64url and hex (both cases) forms are kept. The caller keeps and zeroes {@code bytes}.
   */
  public synchronized void addBytes(byte[] bytes) {
    if (bytes == null || bytes.length < MIN_LENGTH) {
      return;
    }
    addForm(Base64.getEncoder().withoutPadding().encodeToString(bytes).toCharArray());
    addForm(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray());
    char[] hex = HexFormat.of().formatHex(bytes).toCharArray();
    char[] upper = new char[hex.length];
    for (int i = 0; i < hex.length; i++) {
      upper[i] = Character.toUpperCase(hex[i]);
    }
    addForm(hex);
    addForm(upper);
    rebuild();
  }

  /** Adds a login: the password like {@link #add} and {@code Base64(user:password)} (HTTP Basic, URLs). */
  public synchronized void addLogin(CharSequence username, CharSequence password) {
    add(password);
    if (username == null || password == null || password.length() < MIN_LENGTH) {
      return;
    }
    char[] pair = toChars(username + ":" + password);
    byte[] utf8 = utf8(pair);
    try {
      addForm(Base64.getEncoder().withoutPadding().encodeToString(utf8).toCharArray());
    } finally {
      Arrays.fill(utf8, (byte) 0);
      Arrays.fill(pair, '\0');
    }
    rebuild();
  }

  public boolean isEmpty() {
    return index.sorted.length == 0;
  }

  /** The forms to look for, longest first (a value that contains another is masked whole). */
  char[][] forms() {
    return index.sorted;
  }

  /**
   * Whether any form occurs in {@code text}: one pass over it, whatever the number of forms. Nearly every string a
   * call returns holds no secret, and a pass per form (a login alone has a dozen) would make the masker's cost grow
   * with the taint. Also what refuses an exact action that would carry a stored secret: that text can't be masked,
   * since it must reach the approver byte for byte.
   */
  public boolean occursIn(String text) {
    if (text == null) {
      return false;
    }
    Index current = index;
    for (int i = 0, n = text.length(); i < n; i++) {
      char[][] candidates = current.startingWith(text.charAt(i));
      if (candidates == null) {
        continue;
      }
      for (char[] form : candidates) {
        if (startsAt(text, i, form)) {
          return true;
        }
      }
    }
    return false;
  }

  /** Compared char by char: a {@code String} copy of a form would keep the secret beyond {@link #clear()}. */
  private static boolean startsAt(String text, int at, char[] form) {
    if (at + form.length > text.length()) {
      return false;
    }
    for (int j = 1; j < form.length; j++) {
      if (text.charAt(at + j) != form[j]) {
        return false;
      }
    }
    return true;
  }

  /** Zeroes and forgets every value: the call is over. */
  public synchronized void clear() {
    for (char[] form : forms) {
      Arrays.fill(form, '\0');
    }
    forms.clear();
    index = Index.EMPTY;
  }

  @Override
  public String toString() {
    return "Taint[" + index.sorted.length + " forms]";
  }

  private void addForm(char[] form) {
    if (form.length < MIN_LENGTH) {
      Arrays.fill(form, '\0');
      return;
    }
    for (char[] known : forms) {
      if (Arrays.equals(known, form)) {
        Arrays.fill(form, '\0');
        return;
      }
    }
    forms.add(form);
  }

  private void rebuild() {
    char[][] next = forms.toArray(new char[0][]);
    Arrays.sort(next, Comparator.comparingInt((char[] f) -> f.length).reversed());
    index = new Index(next);
  }

  /** The sorted forms and, per first character, the forms that start with it (ASCII by array, the rest by map). */
  private static final class Index {
    static final Index EMPTY = new Index(new char[0][]);

    final char[][] sorted;
    private final char[][][] ascii = new char[128][][];
    private final Map<Character, char[][]> other = new HashMap<>();

    Index(char[][] sorted) {
      this.sorted = sorted;
      Map<Character, List<char[]>> byFirst = new HashMap<>();
      for (char[] form : sorted) {
        byFirst.computeIfAbsent(form[0], c -> new ArrayList<>()).add(form);
      }
      byFirst.forEach((first, list) -> {
        char[][] candidates = list.toArray(new char[0][]);
        if (first < 128) {
          ascii[first] = candidates;
        } else {
          other.put(first, candidates);
        }
      });
    }

    char[][] startingWith(char c) {
      return c < 128 ? ascii[c] : other.isEmpty() ? null : other.get(c);
    }
  }

  private static char[] toChars(CharSequence value) {
    char[] out = new char[value.length()];
    for (int i = 0; i < out.length; i++) {
      out[i] = value.charAt(i);
    }
    return out;
  }

  private static byte[] utf8(char[] chars) {
    ByteBuffer buffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
    byte[] out = new byte[buffer.remaining()];
    buffer.get(out);
    if (buffer.hasArray()) {
      Arrays.fill(buffer.array(), (byte) 0);
    }
    return out;
  }

  /** Percent-encoding of UTF-8 {@code bytes} that keeps letters, digits and {@code kept}. */
  private static char[] percentEncoded(byte[] bytes, String kept, String space) {
    StringBuilder out = new StringBuilder(bytes.length * 3);
    for (byte b : bytes) {
      int c = b & 0xff;
      if (c == ' ') {
        out.append(space);
      } else if (c < 0x80 && (Character.isLetterOrDigit(c) || kept.indexOf(c) >= 0)) {
        out.append((char) c);
      } else {
        out.append('%').append(HEX[c >> 4]).append(HEX[c & 0xf]);
      }
    }
    char[] chars = new char[out.length()];
    out.getChars(0, chars.length, chars, 0);
    out.setLength(0);
    return chars;
  }

  /** What a JSON serializer writing in {@code style} puts between the quotes. */
  static String jsonEscaped(String value, JsonStyle style) {
    StringBuilder out = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '/' -> out.append(style.escapeSlash() ? "\\/" : "/");
        default -> {
          if (c < 0x20 || (style.asciiOnly() && c > 0x7e) || style.htmlChars().indexOf(c) >= 0) {
            out.append(String.format(style.upperHex() ? "\\u%04X" : "\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }
}
