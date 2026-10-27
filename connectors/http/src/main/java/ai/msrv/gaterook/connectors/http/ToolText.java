package ai.msrv.gaterook.connectors.http;

/**
 * Texts an OpenAPI file's author wrote and an agent will read as instructions (tool poisoning, report 10 §6.5):
 * control, bidirectional and zero-width characters are removed, runs of white space collapse, and the text is cut —
 * a tool's description at 1,000 characters, a property's at 400.
 */
final class ToolText {

  static final int TOOL_DESCRIPTION = 1_000;
  static final int PROPERTY_DESCRIPTION = 400;
  static final int TITLE = 120;

  private ToolText() {}

  static String clean(String text, int max) {
    if (text == null) {
      return null;
    }
    if (text.length() <= max && plain(text)) {
      return text; // most texts of a large file: nothing to remove, collapse or cut
    }
    StringBuilder out = new StringBuilder(Math.min(text.length(), max + 16));
    boolean space = false;
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      i += Character.charCount(cp);
      int type = Character.getType(cp);
      if (type == Character.FORMAT || type == Character.SURROGATE || type == Character.PRIVATE_USE
          || type == Character.UNASSIGNED) {
        continue; // bidi overrides, zero-width joiners, BOM, tag characters
      }
      if (Character.isWhitespace(cp) || type == Character.CONTROL || type == Character.LINE_SEPARATOR
          || type == Character.PARAGRAPH_SEPARATOR || cp == 0x00A0) {
        space = !out.isEmpty();
        continue;
      }
      if (space) {
        out.append(' ');
        space = false;
      }
      out.appendCodePoint(cp);
      if (out.length() > max) {
        break;
      }
    }
    if (out.length() > max) {
      int cut = max - 1;
      if (cut > 0 && Character.isHighSurrogate(out.charAt(cut - 1))) {
        cut--; // never half a surrogate pair
      }
      return out.substring(0, cut) + "…";
    }
    return out.toString();
  }

  /** Printable ASCII with single inner spaces only: what {@link #clean} would return unchanged. */
  private static boolean plain(String text) {
    char previous = ' ';
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c < 0x20 || c > 0x7E || (c == ' ' && previous == ' ')) {
        return false;
      }
      previous = c;
    }
    return previous != ' ';
  }
}
