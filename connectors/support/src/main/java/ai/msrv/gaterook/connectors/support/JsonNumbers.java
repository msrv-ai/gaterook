package ai.msrv.gaterook.connectors.support;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Parsed JSON numbers whose text must stay bounded. A number is a value, not a lexeme: {@code 1e1000000000} takes
 * thirteen characters on the wire, but its plain notation takes a billion, and {@link BigDecimal} writes that out
 * ({@code toPlainString}), or computes ten to the billionth for its integer value ({@code toBigInteger}), without
 * complaint; {@code stripTrailingZeros} fails for the largest exponents. Whatever turns a number from an API, a client
 * or an upstream server into text asks here instead.
 *
 * <p>A number is <em>expandable</em> when its exponent adds at most {@link #MAX_PADDING} zeros to its significant
 * digits. Every double is. Any other number is written in scientific notation ({@code 1.5E+1000}), which is as short as
 * the number was when it was read. The size of a number's text is bounded by its digits (the parser limits their
 * count) plus {@code MAX_PADDING}, never by its exponent.
 */
public final class JsonNumbers {

  /**
   * The zeros an exponent may add to the significant digits of a number before the number is no longer written out
   * in plain notation. The longest double ({@code 4.9E-324}, 323 zeros) and the largest
   * ({@code 1.7976931348623157E308}, 292) are within it.
   */
  public static final int MAX_PADDING = 400;

  private JsonNumbers() {}

  /**
   * A number as its significant digits and the scale of those digits ({@link BigDecimal#scale}). Computed with
   * {@code long} arithmetic and from the digits: the scale of the number without its trailing zeros can be outside
   * the range of an {@code int}.
   */
  private record Parts(int signum, String digits, long scale) {

    static Parts of(BigDecimal value) {
      if (value.signum() == 0) {
        return new Parts(0, "0", 0);
      }
      String all = value.unscaledValue().abs().toString();
      int end = all.length();
      while (end > 1 && all.charAt(end - 1) == '0') {
        end--;
      }
      return new Parts(value.signum(), all.substring(0, end), (long) value.scale() - (all.length() - end));
    }

    /** The zeros plain notation adds: trailing zeros for an exponent above the digits, zeros after the point below. */
    long padding() {
      return scale < 0 ? -scale : Math.max(0, scale - digits.length());
    }

    String sign() {
      return signum < 0 ? "-" : "";
    }
  }

  /**
   * Whether {@code node} is NaN or an infinity: the value of a double that a document spelled out of range
   * ({@code 1e999}) or code produced. It has no decimal value ({@code decimalValue()} fails) and JSON has no number
   * for it.
   */
  public static boolean notFinite(JsonNode node) {
    Number number = node.isNumber() ? node.numberValue() : null;
    return number instanceof Double d && !Double.isFinite(d) || number instanceof Float f && !Float.isFinite(f);
  }

  /** Whether plain notation of {@code value} is short enough to write out. */
  public static boolean expandable(BigDecimal value) {
    // No number has more padding than its digits plus the size of its scale: the usual number is settled by that.
    long scale = value.scale();
    long digits = value.precision();
    long upper = scale < 0 ? digits - scale : Math.max(digits, scale);
    return upper <= MAX_PADDING || Parts.of(value).padding() <= MAX_PADDING;
  }

  /**
   * {@code value} without trailing zeros: in plain notation, without a fraction when it has none ({@code 5.0} and
   * {@code 5} are both {@code 5}), as long as the number is expandable; otherwise in scientific notation, also without
   * trailing zeros ({@code 1E-1000000000}, {@code -1.5E+2000}). One text per value, so that digests agree.
   */
  public static String plain(BigDecimal value) {
    if (expandable(value)) {
      // The scale without trailing zeros is within the range of an int here.
      return value.stripTrailingZeros().toPlainString();
    }
    Parts parts = Parts.of(value);
    String digits = parts.digits();
    long exponent = digits.length() - 1 - parts.scale();
    StringBuilder out = new StringBuilder(digits.length() + 24).append(parts.sign()).append(digits.charAt(0));
    if (digits.length() > 1) {
      out.append('.').append(digits, 1, digits.length());
    }
    return out.append('E').append(exponent < 0 ? '-' : '+').append(Math.abs(exponent)).toString();
  }

  /**
   * The plain notation of {@code value} for matching secrets against it: all of it when the number is expandable,
   * otherwise its digits and the first {@link #MAX_PADDING} zeros that follow them, or that follow the decimal point.
   * Any digit string that the whole notation would hold within that many zeros is in this text too.
   */
  public static String checkable(BigDecimal value) {
    if (expandable(value)) {
      return value.stripTrailingZeros().toPlainString();
    }
    Parts parts = Parts.of(value);
    String zeros = "0".repeat(MAX_PADDING);
    return parts.scale() < 0 ? parts.sign() + parts.digits() + zeros : parts.sign() + "0." + zeros + parts.digits();
  }

  private static boolean huge(JsonNode node) {
    return node.isBigDecimal() && !expandable(node.decimalValue());
  }

  /**
   * {@code root} with every number that is not expandable replaced by a string of its scientific notation
   * ({@code "1E-1000000000"}), in place. A reader of an untrusted document calls it on the tree it parsed, so that a
   * number with a runaway exponent does not travel further as a number. Returns {@code root}, or its replacement if the
   * root itself is such a number.
   */
  public static JsonNode keepHugeAsText(JsonNode root) {
    if (root.isNumber()) {
      return huge(root) ? JsonNodeFactory.instance.stringNode(plain(root.decimalValue())) : root;
    }
    ArrayDeque<JsonNode> containers = new ArrayDeque<>();
    containers.push(root);
    while (!containers.isEmpty()) {
      JsonNode node = containers.pop();
      if (node instanceof ObjectNode object) {
        for (Map.Entry<String, JsonNode> entry : object.properties()) {
          JsonNode child = entry.getValue();
          if (child.isNumber()) {
            if (huge(child)) {
              entry.setValue(JsonNodeFactory.instance.stringNode(plain(child.decimalValue())));
            }
          } else if (child.isObject() || child.isArray()) {
            containers.push(child);
          }
        }
      } else if (node instanceof ArrayNode array) {
        for (int i = 0; i < array.size(); i++) {
          JsonNode child = array.get(i);
          if (child.isNumber()) {
            if (huge(child)) {
              array.set(i, JsonNodeFactory.instance.stringNode(plain(child.decimalValue())));
            }
          } else if (child.isObject() || child.isArray()) {
            containers.push(child);
          }
        }
      }
    }
    return root;
  }
}
