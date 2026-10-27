package ai.msrv.gaterook.connectors.kubernetes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Small text helpers: ages as kubectl prints them, plain-text tables, canonical hashes. */
final class K8sText {

  private K8sText() {}

  /** kubectl's {@code HumanDuration}: 45s, 3m20s, 41m, 5h12m, 20h, 3d4h, 41d, 2y30d. */
  static String age(Duration d) {
    long seconds = d.toSeconds();
    if (seconds < -1) {
      return "<invalid>";
    }
    if (seconds < 0) {
      return "0s";
    }
    if (seconds < 120) {
      return seconds + "s";
    }
    long minutes = d.toMinutes();
    if (minutes < 10) {
      long s = seconds % 60;
      return s == 0 ? minutes + "m" : minutes + "m" + s + "s";
    }
    if (minutes < 180) {
      return minutes + "m";
    }
    long hours = d.toHours();
    if (hours < 8) {
      long m = minutes % 60;
      return m == 0 ? hours + "h" : hours + "h" + m + "m";
    }
    if (hours < 48) {
      return hours + "h";
    }
    if (hours < 24 * 8) {
      long h = hours % 24;
      return h == 0 ? hours / 24 + "d" : hours / 24 + "d" + h + "h";
    }
    if (hours < 24 * 365 * 2) {
      return hours / 24 + "d";
    }
    if (hours < 24 * 365 * 8) {
      long dy = (hours / 24) % 365;
      return dy == 0 ? hours / 24 / 365 + "y" : hours / 24 / 365 + "y" + dy + "d";
    }
    return hours / 24 / 365 + "y";
  }

  /** The age of an RFC 3339 timestamp at {@code now}; the text itself when it isn't one. */
  static String ageOf(String timestamp, Instant now) {
    if (timestamp == null) {
      return "<unknown>";
    }
    try {
      return age(Duration.between(OffsetDateTime.parse(timestamp).toInstant(), now));
    } catch (DateTimeParseException e) {
      return timestamp;
    }
  }

  static Instant instant(String timestamp) {
    if (timestamp == null) {
      return null;
    }
    try {
      return OffsetDateTime.parse(timestamp).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  /** Columns padded to their widest cell, upper-case headers, like {@code kubectl get}. */
  static String table(List<String> columns, List<List<String>> rows) {
    int[] width = new int[columns.size()];
    for (int i = 0; i < columns.size(); i++) {
      width[i] = columns.get(i).length();
    }
    for (List<String> row : rows) {
      for (int i = 0; i < row.size() && i < width.length; i++) {
        width[i] = Math.max(width[i], row.get(i).length());
      }
    }
    StringBuilder out = new StringBuilder();
    List<String> header = new ArrayList<>();
    columns.forEach(c -> header.add(c.toUpperCase(java.util.Locale.ROOT)));
    line(out, header, width);
    for (List<String> row : rows) {
      line(out, row, width);
    }
    return out.toString();
  }

  private static void line(StringBuilder out, List<String> cells, int[] width) {
    StringBuilder l = new StringBuilder();
    for (int i = 0; i < cells.size(); i++) {
      String c = cells.get(i);
      l.append(c);
      if (i < cells.size() - 1) {
        l.repeat(' ', Math.max(1, width[i] - c.length() + 3));
      }
    }
    out.append(l.toString().stripTrailing()).append('\n');
  }

  /** SHA-256 of a tree with its keys sorted: the same object hashes the same however the server ordered it. */
  static String sha256(JsonNode tree) {
    try {
      byte[] bytes = K8sApi.JSON.writeValueAsString(sorted(tree)).getBytes(StandardCharsets.UTF_8);
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static JsonNode sorted(JsonNode tree) {
    if (tree.isObject()) {
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(tree.properties());
      entries.sort(Map.Entry.comparingByKey());
      for (Map.Entry<String, JsonNode> e : entries) {
        out.set(e.getKey(), sorted(e.getValue()));
      }
      return out;
    }
    if (tree.isArray()) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      tree.forEach(v -> out.add(sorted(v)));
      return out;
    }
    return tree;
  }

  static String cut(String s, int max) {
    return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
  }

  static String plural(long n, String one, String many) {
    return n + " " + (n == 1 ? one : many);
  }
}
