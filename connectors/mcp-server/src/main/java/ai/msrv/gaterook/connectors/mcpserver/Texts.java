package ai.msrv.gaterook.connectors.mcpserver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Text an MCP server sends about itself is untrusted (ARCHITECTURE.md §9 "Prompt-injection-safe approvals"): names,
 * titles and descriptions of its tools reach the agent and the approver, so control characters, bidirectional
 * overrides and zero-width characters are removed and the length is capped before anything is stored or published.
 */
final class Texts {

  static final JsonMapper JSON = JsonMapper.builder().build();

  private Texts() {}

  /**
   * The text without what could hide or reorder words ({@link #hidden}): C0 and C1 controls (a line break or tab
   * becomes a space unless {@code multiline}), every format character (bidi controls, zero-width characters, the BOM,
   * the tag characters that carry ASCII invisibly), variation selectors and fillers; cut at {@code max} characters (an
   * ellipsis marks the cut).
   */
  static String clean(String text, int max, boolean multiline) {
    if (text == null) {
      return null;
    }
    StringBuilder out = new StringBuilder(Math.min(text.length(), max + 1));
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      i += Character.charCount(cp);
      if (cp == '\n' || cp == '\t' || cp == 0x2028 || cp == 0x2029) {
        out.append(multiline && cp != '\t' ? '\n' : ' ');
      } else if (cp == '\r') {
        // Dropped: \r\n is one line break, a lone \r would overwrite the line in a terminal.
        continue;
      } else if (hidden(cp)) {
        continue;
      } else {
        out.appendCodePoint(cp);
      }
      if (out.length() > max) {
        break;
      }
    }
    String s = out.toString().strip();
    if (s.length() > max) {
      s = s.substring(0, Math.max(0, max - 1)).strip() + "…";
    }
    return s;
  }

  /**
   * Characters that change how text reads without being seen: controls; every format character (category Cf: bidi
   * embeddings, overrides and isolates, zero-width spaces and joiners, the BOM, the soft hyphen, invisible operators,
   * interlinear annotations, and the tag characters U+E0000–E007F, which spell out ASCII an agent reads and a person
   * doesn't see); private-use, unassigned and lone surrogate code points; variation selectors (which can encode bytes
   * as well), the combining grapheme joiner and the Hangul fillers, which render as nothing.
   */
  static boolean hidden(int cp) {
    if (cp < 0x20 || (cp >= 0x7f && cp <= 0x9f)) {
      return true;
    }
    int type = Character.getType(cp);
    if (type == Character.FORMAT || type == Character.PRIVATE_USE || type == Character.UNASSIGNED
        || type == Character.SURROGATE) {
      return true;
    }
    return (cp >= 0xfe00 && cp <= 0xfe0f) || (cp >= 0xe0100 && cp <= 0xe01ef) || cp == 0x034f || cp == 0x115f
        || cp == 0x1160 || cp == 0x3164 || cp == 0xffa0;
  }

  /**
   * Whether a value a call carries (a property name, an {@code enum} value) has a character {@link #hidden} other than a
   * plain control, which JSON shows escaped: such a value can't be cleaned without changing the call.
   */
  static boolean invisible(String text) {
    for (int i = 0; i < text.length(); ) {
      int cp = text.codePointAt(i);
      i += Character.charCount(cp);
      if (cp >= 0x20 && hidden(cp)) {
        return true;
      }
    }
    return false;
  }

  /** A string value of {@code node}, or {@code dflt} when it is missing, null or not a string. */
  static String str(JsonNode node, String dflt) {
    return node != null && node.isString() ? node.asString() : dflt;
  }

  /** SHA-256 of the canonical JSON of {@code node} (keys sorted), hex. */
  static String sha256(JsonNode node) {
    return sha256(JSON.writeValueAsString(canonical(node)));
  }

  static String sha256(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** A copy with object keys in sorted order at every level: the same value always serializes to the same bytes. */
  static JsonNode canonical(JsonNode node) {
    if (node == null) {
      return JsonNodeFactory.instance.nullNode();
    }
    if (node.isObject()) {
      List<String> names = new ArrayList<>(node.propertyNames());
      names.sort(null);
      ObjectNode out = JsonNodeFactory.instance.objectNode();
      for (String name : names) {
        out.set(name, canonical(node.get(name)));
      }
      return out;
    }
    if (node.isArray()) {
      ArrayNode out = JsonNodeFactory.instance.arrayNode();
      for (JsonNode element : node) {
        out.add(canonical(element));
      }
      return out;
    }
    return node;
  }
}
