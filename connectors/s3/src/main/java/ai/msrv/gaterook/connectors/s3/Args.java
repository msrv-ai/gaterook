package ai.msrv.gaterook.connectors.s3;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

/**
 * The arguments of the S3 tools as values the connector may send: every name checked by {@link Names}, every bucket
 * against the connection's list. {@code classify} and {@code execute} read a call through the same methods, so what
 * was let through is what runs.
 */
final class Args {

  private Args() {}

  /** A string argument, or {@code null} when absent or {@code null}. */
  static String text(JsonNode a, String field) throws Refusal {
    JsonNode n = a.get(field);
    if (n == null || n.isNull()) {
      return null;
    }
    if (!n.isString()) {
      throw Refusal.badArgument(field + " must be a string");
    }
    return n.asString();
  }

  /** A bucket of the connection: a valid name on its list. */
  static String bucket(JsonNode a, String field, S3Settings s) throws Refusal {
    String bucket = text(a, field);
    String problem = Names.bucketProblem(bucket, s.aws());
    if (problem != null) {
      throw new Refusal("bad-argument", problem + ".", "Call s3_list_buckets to see the buckets.");
    }
    if (!s.allows(bucket)) {
      throw new Refusal("bucket-not-allowed", bucket + " isn't one of the buckets agents may use on this "
          + "connection.", "Call s3_list_buckets to see the buckets you can use.");
    }
    return bucket;
  }

  /** An object key; {@code write}: a key a change creates, without invisible characters. */
  static String key(JsonNode a, String field, boolean write) throws Refusal {
    String key = text(a, field);
    String problem = write ? Names.newKeyProblem(key) : Names.keyProblem(key);
    if (problem != null) {
      throw Refusal.badArgument(problem);
    }
    return key;
  }

  static String version(JsonNode a, String field) throws Refusal {
    String v = text(a, field);
    if (v != null && v.isEmpty()) {
      return null;
    }
    String problem = Names.versionProblem(v);
    if (problem != null) {
      throw Refusal.badArgument(field + " isn't an S3 version id");
    }
    return v;
  }

  /** User metadata: lower-case names, printable ASCII values, 2 KB in all; {@code null} when absent. */
  static Map<String, String> metadata(JsonNode a) throws Refusal {
    JsonNode m = a.get("metadata");
    if (m == null || m.isNull()) {
      return null;
    }
    if (!m.isObject()) {
      throw Refusal.badArgument("metadata must be an object of names and texts");
    }
    Map<String, String> out = new TreeMap<>();
    int bytes = 0;
    for (Map.Entry<String, JsonNode> e : m.properties()) {
      String name = e.getKey();
      String problem = Names.metadataNameProblem(name);
      if (problem != null) {
        throw Refusal.badArgument(problem);
      }
      if (!e.getValue().isString()) {
        throw Refusal.badArgument("The metadata value of " + name + " must be a string");
      }
      String value = e.getValue().asString();
      problem = Names.metadataValueProblem(name, value);
      if (problem != null) {
        throw Refusal.badArgument(problem);
      }
      bytes += name.getBytes(StandardCharsets.UTF_8).length + value.getBytes(StandardCharsets.UTF_8).length;
      out.put(name, value);
    }
    if (bytes > Names.MAX_METADATA_BYTES) {
      throw Refusal.badArgument("The metadata has " + bytes + " bytes; S3 takes " + Names.MAX_METADATA_BYTES);
    }
    return out;
  }

  /** A non-negative integer argument, {@code dflt} when absent. */
  static long number(JsonNode a, String field, long dflt, long min, long max) throws Refusal {
    JsonNode n = a.get(field);
    if (n == null || n.isNull()) {
      return dflt;
    }
    if (!n.isIntegralNumber() || !n.canConvertToLong() || n.asLong() < min || n.asLong() > max) {
      throw Refusal.badArgument(field + " must be a whole number from " + min + " to " + max);
    }
    return n.asLong();
  }

  static boolean flag(JsonNode a, String field, boolean dflt) throws Refusal {
    JsonNode n = a.get(field);
    if (n == null || n.isNull()) {
      return dflt;
    }
    if (!n.isBoolean()) {
      throw Refusal.badArgument(field + " must be true or false");
    }
    return n.asBoolean();
  }
}
