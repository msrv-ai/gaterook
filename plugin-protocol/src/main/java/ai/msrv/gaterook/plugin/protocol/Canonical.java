package ai.msrv.gaterook.plugin.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;

/**
 * Canonical JSON for hashes both processes must agree on (definition hashes, config hashes): object members sorted by
 * name, no whitespace, numbers and strings as Jackson writes them. Independent of the core's own canonical form, which
 * the plugin host can't see.
 */
public final class Canonical {

  private Canonical() {}

  public static byte[] bytes(JsonNode node) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (JsonGenerator g = GppJson.MAPPER.createGenerator(out)) {
      write(g, node);
    }
    return out.toByteArray();
  }

  public static String text(JsonNode node) {
    return new String(bytes(node), StandardCharsets.UTF_8);
  }

  public static String sha256Hex(JsonNode node) {
    return sha256Hex(bytes(node));
  }

  public static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is missing", e);
    }
  }

  private static void write(JsonGenerator g, JsonNode node) {
    if (node == null || node.isMissingNode()) {
      g.writeNull();
    } else if (node.isObject()) {
      List<Map.Entry<String, JsonNode>> members = new ArrayList<>(node.properties());
      members.sort(Map.Entry.comparingByKey());
      g.writeStartObject();
      for (Map.Entry<String, JsonNode> e : members) {
        g.writeName(e.getKey());
        write(g, e.getValue());
      }
      g.writeEndObject();
    } else if (node.isArray()) {
      g.writeStartArray();
      for (JsonNode item : node) {
        write(g, item);
      }
      g.writeEndArray();
    } else {
      g.writeTree(node);
    }
  }
}
