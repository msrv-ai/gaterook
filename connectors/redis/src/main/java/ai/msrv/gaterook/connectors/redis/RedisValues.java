package ai.msrv.gaterook.connectors.redis;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Redis values as the agent gets them (report 05 §3.4): text when the bytes are valid UTF-8 (a strict decoder: a
 * replaced byte would show a value that isn't there), {@code {"$base64": …}} otherwise; a hash field that isn't UTF-8
 * becomes {@code "base64:<b64>"}. An element longer than the call's cell limit is cut at a character boundary with a
 * marker, before it reaches JSON.
 */
final class RedisValues {

  static final String BASE64_FIELD = "base64:";
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  private RedisValues() {}

  /** The bytes as text, or empty if they aren't valid UTF-8. */
  static Optional<String> utf8(byte[] b) {
    try {
      return Optional.of(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString());
    } catch (CharacterCodingException e) {
      return Optional.empty();
    }
  }

  /** A value as JSON: text, or {@code {"$base64": …}}. */
  static JsonNode value(byte[] b) {
    Optional<String> t = utf8(b);
    return t.isPresent() ? JSON.stringNode(t.get())
        : JSON.objectNode().put("$base64", Base64.getEncoder().encodeToString(b));
  }

  /** A hash field (a JSON property name): text, or {@code base64:<b64>}. */
  static String field(byte[] b) {
    return utf8(b).orElseGet(() -> BASE64_FIELD + Base64.getEncoder().encodeToString(b));
  }

  /**
   * {@code b} cut to {@code max} bytes, at the end of the last whole UTF-8 character when the bytes are text, with
   * the number of bytes left out; {@code null} when it fits.
   */
  static Cut cut(byte[] b, int max) {
    if (b.length <= max) {
      return null;
    }
    // Text loses at most the 1–3 bytes of the character the cut split; binary bytes are kept to the byte.
    byte[] head = withoutPartialCharacter(java.util.Arrays.copyOf(b, Math.max(0, max)));
    return new Cut(head, b.length - head.length);
  }

  /**
   * The head of a longer value without the character the cut split: text that ends in the middle of a UTF-8 sequence
   * would otherwise look binary. Bytes that aren't text with or without their last 1–3 bytes stay as they are.
   */
  static byte[] withoutPartialCharacter(byte[] head) {
    if (utf8(head).isPresent()) {
      return head;
    }
    for (int drop = 1; drop <= 3 && drop < head.length; drop++) {
      byte[] shorter = java.util.Arrays.copyOf(head, head.length - drop);
      if (utf8(shorter).isPresent()) {
        return shorter;
      }
    }
    return head;
  }

  /** A cut value: what is kept and how many bytes were left out. */
  record Cut(byte[] head, long omitted) {}

  /** An element of a collection as JSON, cut to {@code maxBytes} with a marker; {@code cut[0]} says whether it was. */
  static JsonNode element(byte[] b, int maxBytes, boolean[] cut) {
    Cut c = cut(b, maxBytes);
    if (c == null) {
      return value(b);
    }
    cut[0] = true;
    Optional<String> t = utf8(c.head());
    if (t.isPresent()) {
      return JSON.stringNode(t.get() + "…‹truncated " + String.format(java.util.Locale.US, "%,d", c.omitted())
          + " bytes›");
    }
    return JSON.objectNode().put("$base64", Base64.getEncoder().encodeToString(c.head()))
        .put("truncatedBytes", c.omitted());
  }

  /**
   * A key or argument as {@code redis-cli} prints it: in double quotes, {@code \"} and {@code \\} escaped, control
   * bytes as {@code \n \r \t \a \b}, every other byte outside printable ASCII as {@code \xHH}. One spelling per byte
   * string: what the approver reads is exactly what is sent, and no look-alike character can hide in it.
   */
  static String quote(byte[] b) {
    StringBuilder out = new StringBuilder(b.length + 2).append('"');
    for (byte x : b) {
      int c = x & 0xFF;
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '"' -> out.append("\\\"");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case 7 -> out.append("\\a");
        case 8 -> out.append("\\b");
        default -> {
          if (c >= 0x20 && c < 0x7f) {
            out.append((char) c);
          } else {
            out.append(String.format("\\x%02x", c));
          }
        }
      }
    }
    return out.append('"').toString();
  }

  static String quote(String s) {
    return quote(s.getBytes(StandardCharsets.UTF_8));
  }
}
