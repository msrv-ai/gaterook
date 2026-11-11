package ai.msrv.gaterook.core.platform;

import ai.msrv.gaterook.connectors.support.JsonNumbers;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.core.io.JsonStringEncoder;
import tools.jackson.databind.JsonNode;

/**
 * One text per JSON value, whatever order its object keys came in and however its numbers were typed: keys sorted,
 * no whitespace, integral numbers without a fraction ({@code 5.0} and {@code 5} are the same value), other numbers in
 * plain decimal notation. What MACs and digests are computed over (row MACs, manifest and template digests): a value
 * that went through a JSON column and back must give the same bytes.
 *
 * <p>The text of a number is bounded by its digits, not by its exponent: a number whose exponent adds more than
 * {@link JsonNumbers#MAX_PADDING} zeros to its digits ({@code 1e1000000000}) is written in scientific notation without
 * trailing zeros ({@code 1E+1000000000}), and NaN and the infinities, which JSON has no number for, as the strings
 * Jackson stores them as ({@code "Infinity"}). Every other number is written as it always was.
 */
public final class CanonicalJson {

  private CanonicalJson() {}

  public static String text(JsonNode node) {
    StringBuilder out = new StringBuilder(128);
    write(node, out);
    return out.toString();
  }

  public static byte[] bytes(JsonNode node) {
    return text(node).getBytes(StandardCharsets.UTF_8);
  }

  /** Lower-case hex SHA-256 of {@link #bytes}. */
  public static String sha256Hex(JsonNode node) {
    return sha256Hex(bytes(node));
  }

  /** Lower-case hex SHA-256 of canonical bytes already written (one text for both its digest and its size). */
  public static String sha256Hex(byte[] canonical) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void write(JsonNode node, StringBuilder out) {
    if (node == null || node.isNull() || node.isMissingNode()) {
      out.append("null");
    } else if (node.isObject()) {
      List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(node.properties());
      entries.sort(Map.Entry.comparingByKey());
      out.append('{');
      for (int i = 0; i < entries.size(); i++) {
        if (i > 0) {
          out.append(',');
        }
        string(entries.get(i).getKey(), out);
        out.append(':');
        write(entries.get(i).getValue(), out);
      }
      out.append('}');
    } else if (node.isArray()) {
      out.append('[');
      for (int i = 0; i < node.size(); i++) {
        if (i > 0) {
          out.append(',');
        }
        write(node.get(i), out);
      }
      out.append(']');
    } else if (node.isString()) {
      string(node.asString(), out);
    } else if (node.isBoolean()) {
      out.append(node.asBoolean());
    } else if (node.isIntegralNumber()) {
      out.append(node.bigIntegerValue());
    } else if (node.isNumber()) {
      number(node, out);
    } else if (node.isBinary()) {
      string(java.util.Base64.getEncoder().encodeToString(node.binaryValue()), out);
    } else {
      // POJO nodes never reach here: every tree comes from parsed JSON or from code that builds plain nodes.
      string(node.asString(), out);
    }
  }

  private static void number(JsonNode node, StringBuilder out) {
    if (JsonNumbers.notFinite(node)) {
      // They have no decimal value and JSON has no number for them. Jackson stores them as these strings and reads
      // the strings back, so a row that went through a JSON column and back keeps its digest.
      string(node.asString(), out);
      return;
    }
    BigDecimal number = node.decimalValue();
    if (!JsonNumbers.expandable(number)) {
      out.append(JsonNumbers.plain(number));
      return;
    }
    BigDecimal value = number.stripTrailingZeros();
    out.append(value.scale() <= 0 ? value.toBigInteger().toString() : value.toPlainString());
  }

  private static void string(String value, StringBuilder out) {
    out.append('"');
    JsonStringEncoder.getInstance().quoteAsString(value, out);
    out.append('"');
  }
}
