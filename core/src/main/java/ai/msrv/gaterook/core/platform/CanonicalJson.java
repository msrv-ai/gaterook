package ai.msrv.gaterook.core.platform;

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
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(node)));
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
      BigDecimal value = node.decimalValue().stripTrailingZeros();
      out.append(value.scale() <= 0 ? value.toBigInteger().toString() : value.toPlainString());
    } else if (node.isBinary()) {
      string(java.util.Base64.getEncoder().encodeToString(node.binaryValue()), out);
    } else {
      // POJO nodes never reach here: every tree comes from parsed JSON or from code that builds plain nodes.
      string(node.asString(), out);
    }
  }

  private static void string(String value, StringBuilder out) {
    out.append('"');
    JsonStringEncoder.getInstance().quoteAsString(value, out);
    out.append('"');
  }
}
