package ai.msrv.gaterook.connectors.kubernetes;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * JSON trees written as YAML the way kubectl writes them, so that the diff the approver reads lines up with
 * {@code kubectl diff --server-side} and {@code kubectl get -o yaml}. kubectl turns JSON into YAML with
 * {@code sigs.k8s.io/yaml} (go-yaml v2 underneath, libyaml's emitter ported to Go); this is that emitter for the
 * shapes JSON has:
 *
 * <ul>
 *   <li>keys sorted the way go-yaml sorts them (runs of digits compare as numbers, a non-letter before a letter);</li>
 *   <li>a sequence under a key at the key's own indentation;</li>
 *   <li>a string double-quoted when plain text would read back as something else (go-yaml's resolver: booleans of
 *       YAML 1.1, null, numbers, timestamps, base-60 floats), plain if libyaml allows, else single-quoted, else
 *       double-quoted with libyaml's escapes; multi-line strings as literal blocks;</li>
 *   <li>long plain and quoted values folded at the first space past column 80, as libyaml's best width does;</li>
 *   <li>floats as Go's {@code strconv.FormatFloat(f, 'g', -1, 64)}.</li>
 * </ul>
 */
final class YamlText {

  private static final int BEST_WIDTH = 80;
  private static final int BEST_INDENT = 2;

  /** go-yaml v2's resolve map: what plain text reads back as a bool, null or special float. */
  private static final Set<String> RESOLVE_MAP = Set.of("y", "Y", "yes", "Yes", "YES", "true", "True", "TRUE", "on",
      "On", "ON", "n", "N", "no", "No", "NO", "false", "False", "FALSE", "off", "Off", "OFF", "~", "null", "Null",
      "NULL", ".nan", ".NaN", ".NAN", ".inf", ".Inf", ".INF", "+.inf", "+.Inf", "+.INF", "-.inf", "-.Inf", "-.INF");
  private static final Pattern YAML_STYLE_FLOAT = Pattern.compile(
      "[-+]?(\\.[0-9]+|[0-9]+(\\.[0-9]*)?)([eE][-+]?[0-9]+)?");
  private static final Pattern DOT_FLOAT = Pattern.compile("\\.[0-9]+([eE][-+]?[0-9]+)?");
  private static final Pattern BASE60_FLOAT = Pattern.compile("[-+]?[0-9][0-9_]*(:[0-5]?[0-9])+(\\.[0-9_]*)?");
  private static final BigInteger INT64_MIN = BigInteger.valueOf(Long.MIN_VALUE);
  private static final BigInteger INT64_MAX = BigInteger.valueOf(Long.MAX_VALUE);
  private static final BigInteger UINT64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

  private enum Style { PLAIN, SINGLE, DOUBLE, LITERAL }

  private final StringBuilder out = new StringBuilder();

  private YamlText() {}

  static String write(JsonNode tree) {
    YamlText y = new YamlText();
    if (tree.isObject() && !tree.isEmpty()) {
      y.mapping(tree, 0);
    } else if (tree.isArray() && !tree.isEmpty()) {
      y.sequence(tree, 0);
    } else {
      y.scalar(tree, BEST_INDENT);
      y.out.append('\n');
    }
    return y.out.toString();
  }

  // ── block structure ─────────────────────────────────────────────────────────────────────────────────────────

  /** The first key goes where the caller left off: after its own indentation, or after "- " of a sequence item. */
  private void mapping(JsonNode map, int indent) {
    boolean first = true;
    for (Map.Entry<String, JsonNode> e : sortedEntries(map)) {
      if (!first) {
        pad(indent);
      }
      first = false;
      key(e.getKey());
      out.append(':');
      value(e.getValue(), indent);
    }
  }

  private void sequence(JsonNode seq, int indent) {
    boolean first = true;
    for (JsonNode item : seq) {
      if (!first) {
        pad(indent);
      }
      first = false;
      out.append("- ");
      if (item.isObject() && !item.isEmpty()) {
        mapping(item, indent + BEST_INDENT);
      } else if (item.isArray() && !item.isEmpty()) {
        sequence(item, indent + BEST_INDENT);
      } else {
        scalar(item, indent + BEST_INDENT);
        out.append('\n');
      }
    }
  }

  /** What follows {@code key:}: a nested block on the next lines, or a scalar on the same line. */
  private void value(JsonNode v, int indent) {
    if (v.isObject() && !v.isEmpty()) {
      out.append('\n');
      pad(indent + BEST_INDENT);
      mapping(v, indent + BEST_INDENT);
    } else if (v.isArray() && !v.isEmpty()) {
      // go-yaml writes the items of a sequence under a key at the key's own indentation.
      out.append('\n');
      pad(indent);
      sequence(v, indent);
    } else {
      out.append(' ');
      scalar(v, indent + BEST_INDENT);
      out.append('\n');
    }
  }

  // ── scalars ─────────────────────────────────────────────────────────────────────────────────────────────────

  /** A value scalar; {@code indent} is where folded and block lines continue (libyaml's indent of the scalar). */
  private void scalar(JsonNode v, int indent) {
    if (v == null || v.isNull() || v.isMissingNode()) {
      out.append("null");
    } else if (v.isObject()) {
      out.append("{}");
    } else if (v.isArray()) {
      out.append("[]");
    } else if (v.isBoolean()) {
      out.append(v.asBoolean() ? "true" : "false");
    } else if (v.isIntegralNumber()) {
      out.append(v.bigIntegerValue());
    } else if (v.isNumber()) {
      out.append(goFloat(v.doubleValue()));
    } else {
      string(v.asString(), indent, false);
    }
  }

  private void key(String k) {
    string(k, 0, true);
  }

  private void string(String s, int indent, boolean simpleKey) {
    Analysis a = Analysis.of(s);
    Style style = s.indexOf('\n') >= 0 ? Style.LITERAL
        : resolvesToOther(s) || base60(s) ? Style.DOUBLE : Style.PLAIN;
    // libyaml's yaml_emitter_select_scalar_style.
    if (style == Style.LITERAL && (!a.blockAllowed || simpleKey)) {
      style = Style.DOUBLE;
    }
    if (style == Style.PLAIN && (!a.blockPlainAllowed || s.isEmpty() && simpleKey)) {
      style = Style.SINGLE;
    }
    if (style == Style.SINGLE && !a.singleQuotedAllowed) {
      style = Style.DOUBLE;
    }
    boolean breaks = !simpleKey;
    switch (style) {
      case PLAIN -> plain(s, indent, breaks);
      case SINGLE -> singleQuoted(s, indent, breaks);
      case DOUBLE -> doubleQuoted(s, indent, breaks);
      case LITERAL -> literal(s, indent);
    }
  }

  /** A space past the best width becomes a line break (never two in a row, never before another space). */
  private void plain(String s, int indent, boolean allowBreaks) {
    int[] cps = s.codePoints().toArray();
    boolean spaces = false;
    for (int i = 0; i < cps.length; i++) {
      int c = cps[i];
      if (c == ' ') {
        if (allowBreaks && !spaces && column() > BEST_WIDTH && i + 1 < cps.length && cps[i + 1] != ' ') {
          writeIndent(indent);
        } else {
          out.append(' ');
        }
        spaces = true;
      } else {
        out.appendCodePoint(c);
        spaces = false;
      }
    }
  }

  private void singleQuoted(String s, int indent, boolean allowBreaks) {
    int[] cps = s.codePoints().toArray();
    out.append('\'');
    boolean spaces = false;
    for (int i = 0; i < cps.length; i++) {
      int c = cps[i];
      if (c == ' ') {
        if (allowBreaks && !spaces && column() > BEST_WIDTH && i > 0 && i < cps.length - 1 && cps[i + 1] != ' ') {
          writeIndent(indent);
        } else {
          out.append(' ');
        }
        spaces = true;
      } else {
        if (c == '\'') {
          out.append('\'');
        }
        out.appendCodePoint(c);
        spaces = false;
      }
    }
    out.append('\'');
  }

  private void doubleQuoted(String s, int indent, boolean allowBreaks) {
    int[] cps = s.codePoints().toArray();
    out.append('"');
    boolean spaces = false;
    for (int i = 0; i < cps.length; i++) {
      int c = cps[i];
      if (!printable(c) || c == 0xFEFF || isBreak(c) || c == '"' || c == '\\') {
        out.append('\\').append(escape(c));
        spaces = false;
      } else if (c == ' ') {
        if (allowBreaks && !spaces && column() > BEST_WIDTH && i > 0 && i < cps.length - 1) {
          writeIndent(indent);
          if (cps[i + 1] == ' ') {
            out.append('\\');
          }
        } else {
          out.append(' ');
        }
        spaces = true;
      } else {
        out.appendCodePoint(c);
        spaces = false;
      }
    }
    out.append('"');
  }

  private static String escape(int c) {
    return switch (c) {
      case 0x00 -> "0";
      case 0x07 -> "a";
      case 0x08 -> "b";
      case 0x09 -> "t";
      case 0x0A -> "n";
      case 0x0B -> "v";
      case 0x0C -> "f";
      case 0x0D -> "r";
      case 0x1B -> "e";
      case 0x22 -> "\"";
      case 0x5C -> "\\";
      case 0x85 -> "N";
      case 0xA0 -> "_";
      case 0x2028 -> "L";
      case 0x2029 -> "P";
      default -> c <= 0xFF ? String.format("x%02X", c) : c <= 0xFFFF ? String.format("u%04X", c)
          : String.format("U%08X", c);
    };
  }

  /** {@code |}, {@code |-} or {@code |+}, an indentation indicator when the text starts with a space or a break. */
  private void literal(String s, int indent) {
    out.append('|');
    if (s.startsWith(" ") || s.startsWith("\n")) {
      out.append(BEST_INDENT);
    }
    String body;
    if (!s.endsWith("\n")) {
      out.append('-');
      body = s;
    } else if (s.equals("\n") || s.endsWith("\n\n")) {
      out.append('+');
      body = s.substring(0, s.length() - 1);
    } else {
      body = s.substring(0, s.length() - 1);
    }
    for (String line : body.split("\n", -1)) {
      out.append('\n');
      if (!line.isEmpty()) {
        pad(indent);
        out.append(line);
      }
    }
  }

  private void writeIndent(int indent) {
    out.append('\n');
    pad(indent);
  }

  private void pad(int indent) {
    out.repeat(' ', indent);
  }

  /** Characters on the current line, as libyaml counts them (code points). */
  private int column() {
    int nl = out.lastIndexOf("\n");
    return out.codePointCount(nl + 1, out.length());
  }

  // ── libyaml's scalar analysis ───────────────────────────────────────────────────────────────────────────────

  /** {@code yaml_emitter_analyze_scalar}, block context. */
  private record Analysis(boolean blockPlainAllowed, boolean singleQuotedAllowed, boolean blockAllowed) {

    static Analysis of(String s) {
      if (s.isEmpty()) {
        return new Analysis(true, true, false);
      }
      int[] cps = s.codePoints().toArray();
      boolean blockIndicators = s.startsWith("---") || s.startsWith("...");
      boolean special = false;
      boolean lineBreaks = false;
      boolean leadingSpace = false;
      boolean leadingBreak = false;
      boolean trailingSpace = false;
      boolean trailingBreak = false;
      boolean breakSpace = false;
      boolean spaceBreak = false;
      boolean previousSpace = false;
      boolean previousBreak = false;
      boolean precededByWhitespace = true;
      for (int i = 0; i < cps.length; i++) {
        int c = cps[i];
        boolean followedByWhitespace = i + 1 >= cps.length || blankz(cps[i + 1]);
        if (i == 0) {
          if ("#,[]{}&*!|>'\"%@`".indexOf(c) >= 0) {
            blockIndicators = true;
          }
          if ((c == '?' || c == ':' || c == '-') && followedByWhitespace) {
            blockIndicators = true;
          }
        } else {
          if (c == ':' && followedByWhitespace) {
            blockIndicators = true;
          }
          if (c == '#' && precededByWhitespace) {
            blockIndicators = true;
          }
        }
        if (!printable(c)) {
          special = true;
        }
        if (isBreak(c)) {
          lineBreaks = true;
        }
        if (c == ' ') {
          leadingSpace |= i == 0;
          trailingSpace |= i == cps.length - 1;
          breakSpace |= previousBreak;
          previousSpace = true;
          previousBreak = false;
        } else if (isBreak(c)) {
          leadingBreak |= i == 0;
          trailingBreak |= i == cps.length - 1;
          spaceBreak |= previousSpace;
          previousSpace = false;
          previousBreak = true;
        } else {
          previousSpace = false;
          previousBreak = false;
        }
        precededByWhitespace = blankz(c);
      }
      boolean plain = !(leadingSpace || leadingBreak || trailingSpace || trailingBreak || breakSpace || spaceBreak
          || special || lineBreaks || blockIndicators);
      boolean single = !(breakSpace || spaceBreak || special);
      boolean block = !(trailingSpace || spaceBreak || special);
      return new Analysis(plain, single, block);
    }
  }

  /** libyaml's {@code IS_PRINTABLE} for UTF-8: no controls, no C1, no surrogates, nothing above the BMP. */
  private static boolean printable(int c) {
    return c == 0x0A || c >= 0x20 && c <= 0x7E || c >= 0xA0 && c <= 0xD7FF
        || c >= 0xE000 && c <= 0xFFFD && c != 0xFEFF;
  }

  private static boolean isBreak(int c) {
    return c == '\r' || c == '\n' || c == 0x85 || c == 0x2028 || c == 0x2029;
  }

  private static boolean blankz(int c) {
    return c == ' ' || c == '\t' || isBreak(c) || c == 0;
  }

  // ── go-yaml v2's resolver ───────────────────────────────────────────────────────────────────────────────────

  /** Whether {@code resolve("", s)} gives anything but a string: then go-yaml double-quotes it. */
  static boolean resolvesToOther(String s) {
    if (s.isEmpty() || RESOLVE_MAP.contains(s)) {
      return true;
    }
    char c = s.charAt(0);
    if (c == '.') {
      return DOT_FLOAT.matcher(s).matches();
    }
    if (c != '+' && c != '-' && (c < '0' || c > '9')) {
      return false;
    }
    if (timestamp(s)) {
      return true;
    }
    String plain = s.replace("_", "");
    return goInt(plain) || YAML_STYLE_FLOAT.matcher(plain).matches()
        || plain.startsWith("0b") && digits(plain.substring(2), 2) != null
        || plain.startsWith("-0b") && digits(plain.substring(3), 2) != null;
  }

  /** go-yaml quotes base-60 floats of YAML 1.1 ({@code 1:20}) for other parsers' sake. */
  private static boolean base60(String s) {
    return s.indexOf(':') > 0 && BASE60_FLOAT.matcher(s).matches();
  }

  /** {@code strconv.ParseInt(s, 0, 64)} or {@code ParseUint}: a sign, then 0x, 0o, 0b, a leading 0 or decimal. */
  private static boolean goInt(String s) {
    boolean negative = false;
    boolean signed = false;
    String t = s;
    if (t.startsWith("+") || t.startsWith("-")) {
      negative = t.startsWith("-");
      signed = true;
      t = t.substring(1);
    }
    BigInteger v;
    if (t.length() > 1 && t.charAt(0) == '0' && "xXoObB".indexOf(t.charAt(1)) >= 0) {
      char p = Character.toLowerCase(t.charAt(1));
      v = digits(t.substring(2), p == 'x' ? 16 : p == 'o' ? 8 : 2);
    } else if (t.length() > 1 && t.charAt(0) == '0') {
      v = digits(t.substring(1), 8);
    } else {
      v = digits(t, 10);
    }
    if (v == null) {
      return false;
    }
    if (negative) {
      v = v.negate();
    }
    return v.compareTo(INT64_MIN) >= 0 && v.compareTo(INT64_MAX) <= 0 || !signed && v.compareTo(UINT64_MAX) <= 0;
  }

  private static BigInteger digits(String s, int radix) {
    if (s.isEmpty()) {
      return null;
    }
    for (int i = 0; i < s.length(); i++) {
      if (s.charAt(i) > 0x7f || Character.digit(s.charAt(i), radix) < 0) {
        return null;
      }
    }
    return new BigInteger(s, radix);
  }

  /**
   * go-yaml's {@code parseTimestamp}: four digits and a dash, then one of {@code 2006-1-2T15:4:5.999999999Z07:00}
   * (or with {@code t}), {@code 2006-1-2 15:4:5.999999999} and {@code 2006-1-2} as Go's {@code time.Parse} reads them.
   */
  static boolean timestamp(String s) {
    int[] pos = {0};
    if (s.length() < 5 || !fixedDigits(s, pos, 4) || !literal(s, pos, '-')) {
      return false;
    }
    int month = number(s, pos);
    if (month < 1 || month > 12 || !literal(s, pos, '-')) {
      return false;
    }
    int day = number(s, pos);
    if (day < 1 || day > 31) {
      return false;
    }
    if (pos[0] == s.length()) {
      return true;
    }
    char sep = s.charAt(pos[0]);
    if (sep != 'T' && sep != 't' && sep != ' ') {
      return false;
    }
    pos[0]++;
    int hour = number(s, pos);
    if (hour < 0 || hour > 23 || !literal(s, pos, ':')) {
      return false;
    }
    int minute = number(s, pos);
    if (minute < 0 || minute > 59 || !literal(s, pos, ':')) {
      return false;
    }
    int second = number(s, pos);
    if (second < 0 || second > 59) {
      return false;
    }
    if (pos[0] + 1 < s.length() && (s.charAt(pos[0]) == '.' || s.charAt(pos[0]) == ',')
        && isAsciiDigit(s.charAt(pos[0] + 1))) {
      pos[0]++;
      while (pos[0] < s.length() && isAsciiDigit(s.charAt(pos[0]))) {
        pos[0]++;
      }
    }
    if (sep == ' ') {
      return pos[0] == s.length();
    }
    String zone = s.substring(pos[0]);
    return zone.equals("Z") || zone.matches("[-+][0-9]{2}:[0-9]{2}");
  }

  private static boolean fixedDigits(String s, int[] pos, int n) {
    for (int i = 0; i < n; i++) {
      if (pos[0] + i >= s.length() || !isAsciiDigit(s.charAt(pos[0] + i))) {
        return false;
      }
    }
    pos[0] += n;
    return true;
  }

  /** Go's {@code getnum(s, false)}: one digit, or two when the second is a digit too; -1 for none. */
  private static int number(String s, int[] pos) {
    int p = pos[0];
    if (p >= s.length() || !isAsciiDigit(s.charAt(p))) {
      return -1;
    }
    if (p + 1 < s.length() && isAsciiDigit(s.charAt(p + 1))) {
      pos[0] = p + 2;
      return (s.charAt(p) - '0') * 10 + s.charAt(p + 1) - '0';
    }
    pos[0] = p + 1;
    return s.charAt(p) - '0';
  }

  private static boolean literal(String s, int[] pos, char c) {
    if (pos[0] < s.length() && s.charAt(pos[0]) == c) {
      pos[0]++;
      return true;
    }
    return false;
  }

  private static boolean isAsciiDigit(char c) {
    return c >= '0' && c <= '9';
  }

  // ── numbers and keys ────────────────────────────────────────────────────────────────────────────────────────

  /**
   * Go's {@code strconv.FormatFloat(f, 'g', -1, 64)}: the shortest digits, in exponent form when the exponent is
   * below -4 or at least 6 ({@code 1e-07}, {@code 1.5e+06}); go-yaml spells the special values {@code .inf} and
   * {@code .nan}.
   */
  static String goFloat(double f) {
    if (Double.isNaN(f)) {
      return ".nan";
    }
    if (Double.isInfinite(f)) {
      return f > 0 ? ".inf" : "-.inf";
    }
    if (f == 0) {
      return 1 / f < 0 ? "-0" : "0";
    }
    BigDecimal d = new BigDecimal(Double.toString(Math.abs(f))).stripTrailingZeros();
    String digits = d.unscaledValue().toString();
    int nd = digits.length();
    int dp = nd - d.scale();
    int exp = dp - 1;
    StringBuilder s = new StringBuilder(f < 0 ? "-" : "");
    if (exp < -4 || exp >= 6) {
      s.append(digits.charAt(0));
      if (nd > 1) {
        s.append('.').append(digits, 1, nd);
      }
      s.append('e').append(exp < 0 ? '-' : '+');
      int e = Math.abs(exp);
      if (e < 10) {
        s.append('0');
      }
      return s.append(e).toString();
    }
    if (dp <= 0) {
      s.append("0.").repeat('0', -dp).append(digits);
    } else if (dp >= nd) {
      s.append(digits).repeat('0', dp - nd);
    } else {
      s.append(digits, 0, dp).append('.').append(digits, dp, nd);
    }
    return s.toString();
  }

  private static List<Map.Entry<String, JsonNode>> sortedEntries(JsonNode map) {
    List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(map.properties());
    entries.sort((a, b) -> compareKeys(a.getKey(), b.getKey()));
    return entries;
  }

  /**
   * go-yaml v2's key order ({@code keyList.Less}): runs of digits compare as numbers, a non-letter sorts before a
   * letter, letters by code point.
   */
  static int compareKeys(String a, String b) {
    int[] ar = a.codePoints().toArray();
    int[] br = b.codePoints().toArray();
    for (int i = 0; i < ar.length && i < br.length; i++) {
      if (ar[i] == br[i]) {
        continue;
      }
      boolean al = Character.isLetter(ar[i]);
      boolean bl = Character.isLetter(br[i]);
      if (al && bl) {
        return Integer.compare(ar[i], br[i]);
      }
      if (al || bl) {
        return bl ? -1 : 1;
      }
      long an = 0;
      long bn = 0;
      if (ar[i] == '0' || br[i] == '0') {
        for (int j = i - 1; j >= 0 && Character.isDigit(ar[j]); j--) {
          if (ar[j] != '0') {
            an = 1;
            bn = 1;
            break;
          }
        }
      }
      int ai = i;
      for (; ai < ar.length && Character.isDigit(ar[ai]); ai++) {
        an = an * 10 + (ar[ai] - '0');
      }
      int bi = i;
      for (; bi < br.length && Character.isDigit(br[bi]); bi++) {
        bn = bn * 10 + (br[bi] - '0');
      }
      if (an != bn) {
        return Long.compare(an, bn);
      }
      if (ai != bi) {
        return Integer.compare(ai, bi);
      }
      return Integer.compare(ar[i], br[i]);
    }
    return Integer.compare(ar.length, br.length);
  }
}
