package ai.msrv.gaterook.connectors.logs;

import ai.msrv.gaterook.connectors.support.OutputSanitizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * One log entry, normalized across backends (report 07 §3.3). The text a resource wrote is untrusted content: the
 * message and the label values are sanitized here (control characters, ANSI escapes, bidi overrides become visible
 * escapes), the core masks them before anyone sees them.
 *
 * @param nanos    epoch nanoseconds of the entry, the order and the cursor boundary
 * @param id       the backend's own id ({@code insertId}, {@code eventId}, {@code _index/_id}) or a hash of what makes
 *                 a Loki entry unique; two entries at one timestamp are told apart by it
 * @param fields   structured payload (GCP {@code jsonPayload}, OpenSearch {@code _source}, extra Insights fields), or
 *                 {@code null}
 * @param truncated the message or the fields were cut to the per-entry limits
 */
record LogEntry(long nanos, Severity severity, String message, Map<String, String> labels, ObjectNode fields,
    String id, boolean truncated) {

  /** Fields above this many bytes are left out (GCP audit logs carry kilobytes of request detail per entry). */
  static final int MAX_FIELDS_BYTES = 4 * 1024;

  LogEntry {
    labels = labels == null ? Map.of() : Map.copyOf(labels);
  }

  /**
   * An entry from what a backend returned: the message sanitized and cut to {@code maxChars}, labels sorted and
   * sanitized, fields kept only when they fit {@link #MAX_FIELDS_BYTES}.
   */
  static LogEntry of(long nanos, Severity severity, String message, Map<String, String> labels, ObjectNode fields,
      String id, int maxChars) {
    boolean cut = false;
    String text = OutputSanitizer.sanitize(message == null ? "" : message);
    if (text.length() > maxChars) {
      int end = Math.max(0, maxChars - 1);
      if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
        end--;
      }
      text = text.substring(0, end) + "…";
      cut = true;
    }
    TreeMap<String, String> sorted = new TreeMap<>();
    if (labels != null) {
      labels.forEach((k, v) -> {
        if (k != null && v != null) {
          sorted.put(OutputSanitizer.sanitize(k), OutputSanitizer.sanitize(v));
        }
      });
    }
    ObjectNode kept = fields == null ? null : sanitized(fields);
    if (kept != null && (kept.isEmpty() || kept.toString().getBytes(StandardCharsets.UTF_8).length
        > MAX_FIELDS_BYTES)) {
      cut |= !kept.isEmpty();
      kept = null;
    }
    return new LogEntry(nanos, severity == null ? Severity.DEFAULT : severity, text, sorted, kept,
        id == null ? null : OutputSanitizer.sanitize(id), cut);
  }

  /** A copy of a structured payload whose names and strings are sanitized like the message. */
  static ObjectNode sanitized(ObjectNode fields) {
    ObjectNode out = JsonNodeFactory.instance.objectNode();
    fields.properties().forEach(e -> out.set(OutputSanitizer.sanitize(e.getKey()), sanitized(e.getValue())));
    return out;
  }

  private static JsonNode sanitized(JsonNode n) {
    if (n.isObject()) {
      return sanitized((ObjectNode) n);
    }
    if (n.isArray()) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      n.forEach(v -> out.add(sanitized(v)));
      return out;
    }
    return n.isString() ? JsonNodeFactory.instance.stringNode(OutputSanitizer.sanitize(n.asString())) : n;
  }

  /** RFC 3339 in UTC with the precision the backend stores (nanoseconds on Loki and GCP, milliseconds elsewhere). */
  String timestamp() {
    return Instant.ofEpochSecond(Math.floorDiv(nanos, 1_000_000_000L), Math.floorMod(nanos, 1_000_000_000L))
        .toString();
  }

  /** 48 bits of SHA-256 of the id: what a cursor remembers about the entries it has handed out at its boundary. */
  long key() {
    return key(id);
  }

  static long key(String id) {
    byte[] h = sha256(id);
    long k = 0;
    for (int i = 0; i < 6; i++) {
      k = (k << 8) | (h[i] & 0xFF);
    }
    return k;
  }

  /** The structured form of the output schema. */
  ObjectNode toJson() {
    ObjectNode n = JsonNodeFactory.instance.objectNode();
    n.put("timestamp", timestamp());
    n.put("severity", severity.name());
    n.put("message", message);
    if (!labels.isEmpty()) {
      ObjectNode l = n.putObject("labels");
      new TreeMap<>(labels).forEach(l::put);
    }
    if (fields != null) {
      n.set("fields", fields);
    }
    if (id != null) {
      n.put("id", id);
    }
    if (truncated) {
      n.put("truncated", true);
    }
    return n;
  }

  /** {@code base64url(sha256(parts joined by NUL))}, 22 characters: ids for entries without their own. */
  static String hashId(String... parts) {
    StringBuilder b = new StringBuilder();
    for (String p : parts) {
      b.append(p == null ? "" : p).append('\0');
    }
    byte[] h = sha256(b.toString());
    return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(java.util.Arrays.copyOf(h, 16));
  }

  private static byte[] sha256(String s) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
