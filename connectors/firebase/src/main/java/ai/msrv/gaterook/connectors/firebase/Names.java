package ai.msrv.gaterook.connectors.firebase;

import java.nio.charset.StandardCharsets;

/**
 * The names agents put into a Firebase URL — collection and document IDs, database keys — checked before they become
 * one: no control character, no invisible formatting character (zero-width, bidirectional), no broken UTF-16, and none
 * of {@code / \ # ? %}, which a URL or a server could read as something other than a name. What passes is
 * percent-encoded here, every byte but the unreserved characters of RFC 3986, so a name stays one path segment and is
 * never decoded into a dot segment, a query or another path on the way.
 */
final class Names {

  /** Characters no name may hold, in Firestore and in the Realtime Database. */
  static final String FORBIDDEN = "/\\#?%";
  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  private Names() {}

  /**
   * Why {@code name} can't be one segment of a path, or {@code null}.
   *
   * @param forbidden characters refused besides {@link #FORBIDDEN}
   * @param maxBytes  its longest UTF-8
   */
  static String problem(String name, String forbidden, int maxBytes) {
    if (name == null || name.isEmpty()) {
      return "is empty";
    }
    for (int i = 0; i < name.length(); ) {
      char c = name.charAt(i);
      int cp = name.codePointAt(i);
      if (Character.isSurrogate(c) && Character.charCount(cp) == 1) {
        return "holds a broken character";
      }
      if (cp < 0x20 || cp == 0x7f || (cp >= 0x80 && cp <= 0x9f)) {
        return "holds a control character";
      }
      if (FORBIDDEN.indexOf(cp) >= 0 || forbidden.indexOf(cp) >= 0) {
        return "holds " + (cp == '/' ? "a slash" : "the character " + (char) cp);
      }
      int type = Character.getType(cp);
      if (type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
        return "holds an invisible formatting character";
      }
      i += Character.charCount(cp);
    }
    if (utf8Length(name) > maxBytes) {
      return "is longer than " + maxBytes + " bytes";
    }
    return null;
  }

  /** One name as one path segment, or one query value: its UTF-8, every byte but {@code A-Za-z0-9-._~} as {@code %XX}. */
  static String encode(String name) {
    byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
    StringBuilder out = new StringBuilder(bytes.length + 16);
    for (byte b : bytes) {
      int c = b & 0xff;
      if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.'
          || c == '_' || c == '~') {
        out.append((char) c);
      } else {
        out.append('%').append(HEX[c >> 4]).append(HEX[c & 0xf]);
      }
    }
    return out.toString();
  }

  static int utf8Length(String s) {
    return s.getBytes(StandardCharsets.UTF_8).length;
  }

  /** {@code name} for a message: cut, its controls shown as escapes. */
  static String shown(String name) {
    if (name == null) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    int i = 0;
    for (; i < name.length() && i < 80; i++) {
      char c = name.charAt(i);
      if (c < 0x20 || c == 0x7f || (c >= 0x80 && c <= 0x9f) || Character.getType(c) == Character.FORMAT
          || Character.getType(c) == Character.LINE_SEPARATOR || Character.getType(c) == Character.PARAGRAPH_SEPARATOR) {
        out.append(String.format("\\u%04x", (int) c));
      } else {
        out.append(c);
      }
    }
    return i < name.length() ? out + "…" : out.toString();
  }
}
