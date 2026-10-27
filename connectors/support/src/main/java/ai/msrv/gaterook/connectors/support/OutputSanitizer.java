package ai.msrv.gaterook.connectors.support;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Text from resources is untrusted content: command output (SSH), logs of pods, bodies of HTTP APIs (reports 06 §3.3,
 * 08, 10 §3.3). Before it reaches the agent, the approver or the audit log, every control character but {@code \n} and
 * {@code \t}, the escape that starts ANSI CSI/OSC sequences, C1 controls, bidirectional overrides and zero-width
 * characters are replaced by visible escapes ({@code \e[31m}, {@code \x07}, {@code ‮}). Nothing is dropped:
 * what the resource said stays readable, but it can no longer recolour a terminal, hide text or reorder a line.
 *
 * <p>{@code \r\n} becomes {@code \n}; a lone {@code \r} (progress bars that overwrite a line) becomes {@code \r}.
 * Bytes that aren't UTF-8 are decoded with U+FFFD.
 */
public final class OutputSanitizer {

  private OutputSanitizer() {}

  /** Decodes UTF-8 with replacement and sanitizes. */
  public static String decode(byte[] bytes) {
    return decode(bytes, 0, bytes.length);
  }

  public static String decode(byte[] bytes, int offset, int length) {
    CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE);
    try {
      CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes, offset, length));
      return sanitize(chars);
    } catch (CharacterCodingException e) {
      // REPLACE never throws; keep the compiler happy.
      throw new IllegalStateException(e);
    }
  }

  /** Sanitizes text that is already decoded. */
  public static String sanitize(CharSequence text) {
    if (text == null) {
      return null;
    }
    StringBuilder out = null;
    int length = text.length();
    for (int i = 0; i < length; i++) {
      char c = text.charAt(i);
      String replacement = replacement(c, i + 1 < length ? text.charAt(i + 1) : 0);
      if (replacement == null) {
        if (out != null) {
          out.append(c);
        }
        continue;
      }
      if (out == null) {
        out = new StringBuilder(length + 16);
        out.append(text, 0, i);
      }
      if (replacement.isEmpty()) {
        continue; // the \r of \r\n
      }
      out.append(replacement);
    }
    return out == null ? text.toString() : out.toString();
  }

  /** Whether {@link #sanitize} would change {@code text}. */
  public static boolean isClean(CharSequence text) {
    for (int i = 0; i < text.length(); i++) {
      if (replacement(text.charAt(i), i + 1 < text.length() ? text.charAt(i + 1) : 0) != null) {
        return false;
      }
    }
    return true;
  }

  /** The escape for {@code c}, {@code ""} to drop it, {@code null} to keep it. */
  private static String replacement(char c, char next) {
    if (c == '\n' || c == '\t') {
      return null;
    }
    if (c == '\r') {
      return next == '\n' ? "" : "\\r";
    }
    if (c == 0x1B) {
      return "\\e";
    }
    if (c < 0x20 || c == 0x7F) {
      return String.format("\\x%02x", (int) c);
    }
    if (c >= 0x80 && c <= 0x9F) {
      return String.format("\\u%04X", (int) c);
    }
    if (invisible(c)) {
      return String.format("\\u%04X", (int) c);
    }
    return null;
  }

  /** Bidi controls and zero-width characters: they change what a reader sees without being seen. */
  static boolean invisible(char c) {
    return (c >= 0x202A && c <= 0x202E)   // LRE RLE PDF LRO RLO
        || (c >= 0x2066 && c <= 0x2069)   // LRI RLI FSI PDI
        || c == 0x200E || c == 0x200F     // LRM RLM
        || c == 0x061C                    // ALM
        || (c >= 0x200B && c <= 0x200D)   // ZWSP ZWNJ ZWJ
        || c == 0x2060 || c == 0xFEFF     // WJ, BOM / ZWNBSP
        || c == 0x00AD;                   // soft hyphen
  }
}
