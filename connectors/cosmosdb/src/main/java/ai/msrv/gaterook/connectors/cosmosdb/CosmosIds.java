package ai.msrv.gaterook.connectors.cosmosdb;

import java.nio.charset.StandardCharsets;

/**
 * The IDs agents put into a Cosmos DB resource link — database, container and item IDs — checked before they become
 * part of one. Cosmos DB refuses {@code /} and {@code \} in any ID, and {@code ?} and {@code #} in database and
 * container names; GateRook also refuses control characters, invisible formatting characters (zero-width,
 * bidirectional), broken UTF-16 and the dot segments {@code .} and {@code ..}. What passes is percent-encoded here,
 * every byte but the unreserved characters of RFC 3986, so an ID stays one path segment on the wire. The signature of
 * a request covers the ID as it is, not its encoding (Cosmos DB's resource link).
 */
final class CosmosIds {

  /** Database and container names: Cosmos DB's limit, in characters. */
  static final int MAX_NAME = 255;
  /** Item IDs: Cosmos DB's limit, in UTF-8 bytes. */
  static final int MAX_ITEM_ID_BYTES = 1_023;
  private static final char[] HEX = "0123456789ABCDEF".toCharArray();

  private CosmosIds() {}

  /** Why {@code id} can't be a database or container name, or {@code null}. */
  static String nameProblem(String id) {
    if (id == null || id.isEmpty()) {
      return "is empty";
    }
    if (id.length() > MAX_NAME) {
      return "is longer than " + MAX_NAME + " characters";
    }
    if (id.endsWith(" ")) {
      return "ends with a space";
    }
    String common = common(id, "/\\?#");
    if (common != null) {
      return common;
    }
    return null;
  }

  /** Why {@code id} can't be an item ID, or {@code null}. */
  static String itemProblem(String id) {
    if (id == null || id.isEmpty()) {
      return "is empty";
    }
    String common = common(id, "/\\");
    if (common != null) {
      return common;
    }
    if (utf8Length(id) > MAX_ITEM_ID_BYTES) {
      return "is longer than " + MAX_ITEM_ID_BYTES + " bytes";
    }
    return null;
  }

  private static String common(String id, String forbidden) {
    if (id.equals(".") || id.equals("..")) {
      return "is a dot segment";
    }
    for (int i = 0; i < id.length(); ) {
      char c = id.charAt(i);
      int cp = id.codePointAt(i);
      if (Character.isSurrogate(c) && Character.charCount(cp) == 1) {
        return "holds a broken character";
      }
      if (cp < 0x20 || cp == 0x7f || (cp >= 0x80 && cp <= 0x9f)) {
        return "holds a control character";
      }
      if (forbidden.indexOf(cp) >= 0) {
        return "holds " + (cp == '/' ? "a slash" : cp == '\\' ? "a backslash" : "the character " + (char) cp);
      }
      int type = Character.getType(cp);
      if (type == Character.FORMAT || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
        return "holds an invisible formatting character";
      }
      i += Character.charCount(cp);
    }
    return null;
  }

  /** One ID as one path segment: its UTF-8, every byte but {@code A-Za-z0-9-._~} as {@code %XX}. */
  static String encode(String id) {
    byte[] bytes = id.getBytes(StandardCharsets.UTF_8);
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

  /** {@code id} for a message: cut, its controls and invisible characters shown as escapes. */
  static String shown(String id) {
    if (id == null) {
      return "";
    }
    StringBuilder out = new StringBuilder();
    int i = 0;
    for (; i < id.length() && i < 80; i++) {
      char c = id.charAt(i);
      int type = Character.getType(c);
      if (c < 0x20 || c == 0x7f || (c >= 0x80 && c <= 0x9f) || type == Character.FORMAT
          || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR || Character.isSurrogate(c)
          && !(Character.isHighSurrogate(c) && i + 1 < id.length() && Character.isLowSurrogate(id.charAt(i + 1)))
          && !(Character.isLowSurrogate(c) && i > 0 && Character.isHighSurrogate(id.charAt(i - 1)))) {
        out.append(String.format("\\u%04x", (int) c));
      } else {
        out.append(c);
      }
    }
    return i < id.length() ? out + "…" : out.toString();
  }
}
