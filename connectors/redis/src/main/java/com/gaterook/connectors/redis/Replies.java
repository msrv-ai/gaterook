package com.gaterook.connectors.redis;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Raw RESP2 replies ({@code byte[]}, {@code Long}, {@code List<Object>}, {@code null}) read without surprises. */
final class Replies {

  private Replies() {}

  /** A whole bulk string; {@code null} for anything else, a {@link BoundedConnection.Cut} included. */
  static byte[] bytes(Object reply) {
    return reply instanceof byte[] b ? b : null;
  }

  /** A bulk string's bytes, or the head of a {@link BoundedConnection.Cut}. */
  static byte[] head(Object reply) {
    return reply instanceof BoundedConnection.Cut c ? c.head() : bytes(reply);
  }

  /** A status or bulk reply as text (UTF-8, lenient: names and numbers only, never values for the agent). */
  static String text(Object reply) {
    if (reply instanceof byte[] b) {
      return new String(b, StandardCharsets.UTF_8);
    }
    return reply == null ? null : String.valueOf(reply);
  }

  static long integer(Object reply, long dflt) {
    if (reply instanceof Long l) {
      return l;
    }
    if (reply instanceof byte[] b) {
      try {
        return Long.parseLong(new String(b, StandardCharsets.US_ASCII));
      } catch (NumberFormatException e) {
        return dflt;
      }
    }
    return dflt;
  }

  @SuppressWarnings("unchecked")
  static List<Object> list(Object reply) {
    return reply instanceof List<?> l ? (List<Object>) l : List.of();
  }

  /** A flat {@code [name, value, name, value…]} reply (HELLO, ACL GETUSER in RESP2) as a map, in order. */
  static Map<String, Object> pairs(Object reply) {
    Map<String, Object> out = new LinkedHashMap<>();
    List<Object> l = list(reply);
    for (int i = 0; i + 1 < l.size(); i += 2) {
      out.put(text(l.get(i)), l.get(i + 1));
    }
    return out;
  }
}
